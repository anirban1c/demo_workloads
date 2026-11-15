package com.demo.workloads.funds;

import com.demo.workloads.common.events.EventEnvelope;
import com.demo.workloads.common.idempotency.ProcessedEvents;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class FundsService {
    private final FundsVendors vendors;
    private final JdbcTemplate jdbc;
    private final ProcessedEvents processed;
    private final TransactionTemplate tx;

    public FundsService(FundsVendors vendors, JdbcTemplate jdbc, ProcessedEvents processed, TransactionTemplate tx) {
        this.vendors = vendors;
        this.jdbc = jdbc;
        this.processed = processed;
        this.tx = tx;
    }

    /**
     * Charge via Paylane, credit the ledger at Corebank, record the transaction. Vendor calls carry the command's
     * idempotency key, so a redelivery or retry-topic replay never double-charges. Returns empty on duplicates.
     */
    public Optional<Map<String, Object>> addFunds(EventEnvelope cmd) {
        if (processed.alreadyProcessed(cmd.eventId())) return Optional.empty();
        String accountId = cmd.entityId();
        BigDecimal amount = new BigDecimal(cmd.str("amount"));
        String currency = Optional.ofNullable(cmd.str("currency")).orElse("GBP");
        if (amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");

        String chargeId = vendors.charge(accountId, amount, currency, cmd.idempotencyKey());
        String ledgerId = vendors.postLedger(accountId, amount, currency, cmd.idempotencyKey());

        String txId = UUID.randomUUID().toString();
        return tx.execute(status -> {
            if (!processed.markProcessed(cmd.eventId())) return Optional.<Map<String, Object>>empty();
            jdbc.update("""
                    insert into funds_transactions(id, request_id, account_id, user_id, amount, currency, status,
                                                   charge_id, ledger_entry_id)
                    values (?, ?, ?, ?, ?, ?, 'COMPLETED', ?, ?)""",
                    txId, cmd.requestId(), accountId, cmd.userId(), amount, currency, chargeId, ledgerId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("transactionId", txId);
            result.put("accountId", accountId);
            result.put("amount", amount);
            result.put("currency", currency);
            result.put("chargeId", chargeId);
            result.put("ledgerEntryId", ledgerId);
            return Optional.of(result);
        });
    }

    public void recordRejected(EventEnvelope cmd, String reason) {
        tx.executeWithoutResult(status -> {
            if (!processed.markProcessed(cmd.eventId())) return;
            jdbc.update("""
                    insert into funds_transactions(id, request_id, account_id, user_id, amount, currency, status, error)
                    values (?, ?, ?, ?, ?, ?, 'REJECTED', ?)""",
                    UUID.randomUUID().toString(), cmd.requestId(), cmd.entityId(), cmd.userId(),
                    new BigDecimal(cmd.str("amount")), Optional.ofNullable(cmd.str("currency")).orElse("GBP"), reason);
        });
    }
}
