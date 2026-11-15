package com.demo.workloads.common.events;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One envelope for every command and event on the bus.
 *
 * @param eventId        unique per message; consumers dedupe on it
 * @param requestId      the client-visible async request id (gateway status key)
 * @param type           e.g. AddFunds, FundsAdded, CreateUser, UserCreated
 * @param journey        user journey name (also travels as W3C baggage)
 * @param userId         acting user
 * @param entityId       partition key: accountId / userId
 * @param idempotencyKey passed to vendors so retries never double-apply
 * @param status         PENDING for commands; COMPLETED / FAILED for outcome events
 * @param payload        type-specific body
 * @param error          failure reason on FAILED events
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventEnvelope(
        String eventId,
        String requestId,
        String type,
        String journey,
        String userId,
        String entityId,
        String idempotencyKey,
        RequestStatus status,
        Instant occurredAt,
        Map<String, Object> payload,
        String error) {

    public static EventEnvelope command(String requestId, String type, String journey, String userId,
                                        String entityId, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID().toString(), requestId, type, journey, userId, entityId,
                requestId, RequestStatus.PENDING, Instant.now(), payload, null);
    }

    /** Outcome event derived from a command, keeping correlation fields. */
    public EventEnvelope completed(String eventType, Map<String, Object> result) {
        return new EventEnvelope(UUID.randomUUID().toString(), requestId, eventType, journey, userId, entityId,
                idempotencyKey, RequestStatus.COMPLETED, Instant.now(), result, null);
    }

    public EventEnvelope failed(String eventType, String reason) {
        return new EventEnvelope(UUID.randomUUID().toString(), requestId, eventType, journey, userId, entityId,
                idempotencyKey, RequestStatus.FAILED, Instant.now(), Map.of(), reason);
    }

    public String str(String key) {
        Object v = payload == null ? null : payload.get(key);
        return v == null ? null : v.toString();
    }
}
