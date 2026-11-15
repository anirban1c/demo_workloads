package com.demo.workloads.gateway.status;

import com.demo.workloads.common.events.EventEnvelope;
import com.demo.workloads.common.events.EventPublisher;
import com.demo.workloads.common.events.Topics;
import com.demo.workloads.common.journey.Journey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Folds outcome events into request status and records end-to-end async completion time per journey. */
@Component
public class StatusProjector {
    private final RequestStatusStore store;
    private final EventPublisher publisher;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public StatusProjector(RequestStatusStore store, EventPublisher publisher, ObjectMapper mapper, MeterRegistry meters) {
        this.store = store;
        this.publisher = publisher;
        this.mapper = mapper;
        this.meters = meters;
    }

    @KafkaListener(id = "gateway-status", topics = {Topics.FUNDS_EVENTS, Topics.USER_EVENTS, Topics.PROFILE_EVENTS,
            Topics.INSIGHTS_EVENTS})
    public void onEvent(String json) throws JsonProcessingException {
        EventEnvelope e = publisher.parse(json);
        Journey.tagCurrentSpan();
        if (e.requestId() == null || !e.status().terminal()) return;
        Map<String, String> before = store.get(e.requestId());
        store.resolve(e.requestId(), e.status(), e.type(),
                e.payload() == null ? null : mapper.writeValueAsString(e.payload()), e.error());
        String created = before.get("createdAt");
        if (created != null) {
            Timer.builder("journey.async.completion")
                    .description("Edge accept (202) to outcome event, per async journey")
                    .tag("journey", e.journey() == null ? "unknown" : e.journey())
                    .tag("status", e.status().name())
                    .publishPercentileHistogram()
                    .register(meters)
                    .record(Duration.between(Instant.parse(created), Instant.now()));
        }
    }
}
