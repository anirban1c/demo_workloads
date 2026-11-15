package com.demo.workloads.common.kafka;

import com.demo.workloads.common.events.EventEnvelope;
import com.demo.workloads.common.events.EventPublisher;
import com.demo.workloads.common.events.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.charset.StandardCharsets;

/**
 * Generic DLT handler for every command topic: once retries are exhausted the command is parked on *-dlt and a
 * FAILED outcome is published so the gateway's request status resolves instead of hanging in PENDING.
 */
public class DeadLetterFailurePublisher {
    private static final Logger log = LoggerFactory.getLogger(DeadLetterFailurePublisher.class);

    private final EventPublisher publisher;
    private final MeterRegistry meters;

    public DeadLetterFailurePublisher(EventPublisher publisher, MeterRegistry meters) {
        this.publisher = publisher;
        this.meters = meters;
    }

    public void onDeadLetter(ConsumerRecord<String, String> record) {
        String original = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC, record.topic().replaceAll("-dlt$", ""));
        String reason = header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE, "retries exhausted");
        meters.counter("commands.dead_lettered", "topic", original).increment();
        try {
            EventEnvelope cmd = publisher.parse(record.value());
            publisher.publishSync(Topics.eventsFor(original), cmd.failed(cmd.type() + "Failed", "dead-lettered: " + reason));
            log.error("dead-lettered {} request={} reason={}", cmd.type(), cmd.requestId(), reason);
        } catch (IllegalArgumentException e) {
            log.error("unparseable dead letter on {}: {}", record.topic(), e.getMessage());
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name, String fallback) {
        Header h = record.headers().lastHeader(name);
        return h == null ? fallback : new String(h.value(), StandardCharsets.UTF_8);
    }
}
