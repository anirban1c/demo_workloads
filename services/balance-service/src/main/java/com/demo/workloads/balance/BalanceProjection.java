package com.demo.workloads.balance;

import com.demo.workloads.common.events.EventEnvelope;
import com.demo.workloads.common.events.EventPublisher;
import com.demo.workloads.common.events.RequestStatus;
import com.demo.workloads.common.events.Topics;
import com.demo.workloads.common.idempotency.ProcessedEvents;
import com.demo.workloads.common.journey.Journey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * CQRS read model: balances are projected from funds.events and user.events, so get_balances never waits on the
 * core-banking vendor in the steady state.
 */
@Component
public class BalanceProjection {
    private final JdbcTemplate jdbc;
    private final ProcessedEvents processed;
    private final EventPublisher publisher;

    public BalanceProjection(JdbcTemplate jdbc, ProcessedEvents processed, EventPublisher publisher) {
        this.jdbc = jdbc;
        this.processed = processed;
        this.publisher = publisher;
    }

    @Transactional
    @KafkaListener(id = "balance-projection", topics = {Topics.FUNDS_EVENTS, Topics.USER_EVENTS})
    public void onEvent(String json) {
        EventEnvelope e = publisher.parse(json);
        Journey.tagCurrentSpan();
        if (e.status() != RequestStatus.COMPLETED || !processed.markProcessed(e.eventId())) return;
        switch (e.type()) {
            case "FundsAdded" -> jdbc.update("""
                    insert into balances(account_id, user_id, available, currency, source)
                    values (?, ?, ?, ?, 'projection')
                    on conflict (account_id) do update
                      set available = balances.available + excluded.available, updated_at = now()""",
                    e.str("accountId"), e.userId(), new BigDecimal(e.str("amount")), e.str("currency"));
            case "UserCreated" -> jdbc.update("""
                    insert into balances(account_id, user_id, available, currency, source)
                    values (?, ?, 0, 'GBP', 'projection') on conflict do nothing""",
                    e.str("accountId"), e.userId());
            case "UserDeleted" -> jdbc.update("delete from balances where user_id = ?", e.userId());
            default -> { }
        }
    }
}
