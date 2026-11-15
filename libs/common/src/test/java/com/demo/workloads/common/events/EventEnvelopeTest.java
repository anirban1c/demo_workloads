package com.demo.workloads.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventEnvelopeTest {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void outcomeEventsKeepCorrelationFields() {
        EventEnvelope cmd = EventEnvelope.command("req-1", "AddFunds", "add_funds", "u-1", "ACC-u-1", Map.of("amount", "10"));
        EventEnvelope done = cmd.completed("FundsAdded", Map.of("transactionId", "t-1"));
        EventEnvelope failed = cmd.failed("AddFundsFailed", "declined");

        assertThat(done.requestId()).isEqualTo("req-1");
        assertThat(done.idempotencyKey()).isEqualTo(cmd.idempotencyKey());
        assertThat(done.entityId()).isEqualTo("ACC-u-1");
        assertThat(done.eventId()).isNotEqualTo(cmd.eventId());
        assertThat(done.status()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(failed.status()).isEqualTo(RequestStatus.FAILED);
        assertThat(failed.error()).isEqualTo("declined");
    }

    @Test
    void roundTripsThroughJson() throws Exception {
        EventEnvelope cmd = EventEnvelope.command("req-1", "CreateUser", "user_crud", "u-1", "u-1", Map.of("email", "a@b.c"));
        EventEnvelope back = mapper.readValue(mapper.writeValueAsString(cmd), EventEnvelope.class);
        assertThat(back).isEqualTo(cmd);
    }

    @Test
    void commandTopicsMapToTheirEventTopics() {
        assertThat(Topics.eventsFor(Topics.FUNDS_COMMANDS)).isEqualTo(Topics.FUNDS_EVENTS);
        assertThat(Topics.eventsFor(Topics.INSIGHTS_REQUESTS)).isEqualTo(Topics.INSIGHTS_EVENTS);
        assertThat(Topics.eventsFor(Topics.PROFILE_COMMANDS)).isEqualTo(Topics.PROFILE_EVENTS);
    }
}
