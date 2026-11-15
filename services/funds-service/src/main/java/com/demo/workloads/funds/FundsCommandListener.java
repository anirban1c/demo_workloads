package com.demo.workloads.funds;

import com.demo.workloads.common.events.EventEnvelope;
import com.demo.workloads.common.events.EventPublisher;
import com.demo.workloads.common.events.Topics;
import com.demo.workloads.common.journey.Journey;
import com.demo.workloads.common.vendor.VendorRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * add_funds consumer. Transient vendor failures propagate as VendorUnavailableException, which the shared retry
 * configuration routes to funds.commands-retry-* topics and finally funds.commands-dlt.
 */
@Component
public class FundsCommandListener {
    private static final Logger log = LoggerFactory.getLogger(FundsCommandListener.class);

    private final FundsService funds;
    private final EventPublisher publisher;

    public FundsCommandListener(FundsService funds, EventPublisher publisher) {
        this.funds = funds;
        this.publisher = publisher;
    }

    @KafkaListener(id = "funds-commands", topics = Topics.FUNDS_COMMANDS)
    public void onCommand(String json) {
        EventEnvelope cmd = publisher.parse(json);
        Journey.tagCurrentSpan();
        if (!"AddFunds".equals(cmd.type())) {
            log.warn("ignoring unknown command {}", cmd.type());
            return;
        }
        try {
            funds.addFunds(cmd).ifPresent(result ->
                    publisher.publishSync(Topics.FUNDS_EVENTS, cmd.completed("FundsAdded", result)));
        } catch (VendorRejectedException | IllegalArgumentException e) {
            funds.recordRejected(cmd, e.getMessage());
            publisher.publishSync(Topics.FUNDS_EVENTS, cmd.failed("AddFundsFailed", e.getMessage()));
        }
    }
}
