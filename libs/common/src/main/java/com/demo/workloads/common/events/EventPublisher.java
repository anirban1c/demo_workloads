package com.demo.workloads.common.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Publishes envelopes as JSON strings keyed by entity id (keeps per-entity ordering within a partition). */
public class EventPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public EventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    public CompletableFuture<?> publish(String topic, EventEnvelope envelope) {
        return kafka.send(topic, envelope.entityId(), toJson(envelope));
    }

    /** Publish and wait for the broker ack (used by consumers so the outcome is durable before the offset commits). */
    public void publishSync(String topic, EventEnvelope envelope) {
        try {
            publish(topic, envelope).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("publish to " + topic + " failed", e);
        }
    }

    public EventEnvelope parse(String json) {
        try {
            return mapper.readValue(json, EventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("bad envelope: " + e.getOriginalMessage(), e);
        }
    }

    public String toJson(EventEnvelope envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
