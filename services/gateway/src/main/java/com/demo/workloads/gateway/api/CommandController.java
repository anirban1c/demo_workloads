package com.demo.workloads.gateway.api;

import com.demo.workloads.common.events.EventEnvelope;
import com.demo.workloads.common.events.EventPublisher;
import com.demo.workloads.common.events.Topics;
import com.demo.workloads.gateway.journey.JourneyFilter.JourneyContext;
import com.demo.workloads.gateway.status.RequestStatusStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Writes are accepted, put on the bus and acknowledged with 202 + a status URL. The broker absorbs bursts; the
 * owning service drains its command topic at the rate its vendors allow.
 */
@RestController
@RequestMapping("/api")
public class CommandController {
    private final EventPublisher publisher;
    private final RequestStatusStore statuses;

    public CommandController(EventPublisher publisher, RequestStatusStore statuses) {
        this.publisher = publisher;
        this.statuses = statuses;
    }

    public record AddFunds(@NotNull @DecimalMin("0.01") BigDecimal amount, String currency) {}

    public record CreateUser(@NotBlank @Email String email, @NotBlank String firstName, @NotBlank String lastName,
                             String dateOfBirth) {}

    @PostMapping("/accounts/{accountId}/funds")
    public ResponseEntity<Map<String, Object>> addFunds(@PathVariable String accountId, @Valid @RequestBody AddFunds body,
                                                        @RequestAttribute("journey") JourneyContext j) {
        Map<String, Object> p = new HashMap<>();
        p.put("amount", body.amount().toPlainString());
        p.put("currency", body.currency() == null ? "GBP" : body.currency());
        return submit(Topics.FUNDS_COMMANDS, "AddFunds", accountId, p, j);
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(@Valid @RequestBody CreateUser body,
                                                          @RequestAttribute("journey") JourneyContext j) {
        String userId = UUID.randomUUID().toString();
        Map<String, Object> p = new HashMap<>();
        p.put("email", body.email());
        p.put("firstName", body.firstName());
        p.put("lastName", body.lastName());
        if (body.dateOfBirth() != null) p.put("dateOfBirth", body.dateOfBirth());
        return submit(Topics.USER_COMMANDS, "CreateUser", userId,
                p, new JourneyContext(j.name(), j.id(), j.step(), userId));
    }

    @PutMapping("/users/{userId}")
    public ResponseEntity<Map<String, Object>> updateUser(@PathVariable String userId, @RequestBody Map<String, Object> body,
                                                          @RequestAttribute("journey") JourneyContext j) {
        return submit(Topics.USER_COMMANDS, "UpdateUser", userId, body, j);
    }

    @DeleteMapping("/users/{userId}")
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable String userId,
                                                          @RequestAttribute("journey") JourneyContext j) {
        return submit(Topics.USER_COMMANDS, "DeleteUser", userId, Map.of(), j);
    }

    @PutMapping("/users/{userId}/profile")
    public ResponseEntity<Map<String, Object>> upsertProfile(@PathVariable String userId, @RequestBody Map<String, Object> body,
                                                             @RequestAttribute("journey") JourneyContext j) {
        return submit(Topics.PROFILE_COMMANDS, "UpsertProfile", userId, body, j);
    }

    @PostMapping("/users/{userId}/profile")
    public ResponseEntity<Map<String, Object>> createProfile(@PathVariable String userId, @RequestBody Map<String, Object> body,
                                                             @RequestAttribute("journey") JourneyContext j) {
        return upsertProfile(userId, body, j);
    }

    @DeleteMapping("/users/{userId}/profile")
    public ResponseEntity<Map<String, Object>> deleteProfile(@PathVariable String userId,
                                                             @RequestAttribute("journey") JourneyContext j) {
        return submit(Topics.PROFILE_COMMANDS, "DeleteProfile", userId, Map.of(), j);
    }

    @PostMapping("/users/{userId}/insights")
    public ResponseEntity<Map<String, Object>> generateInsights(@PathVariable String userId,
                                                                @RequestBody(required = false) Map<String, Object> body,
                                                                @RequestAttribute("journey") JourneyContext j) {
        Map<String, Object> p = new HashMap<>();
        p.put("period", body == null ? "30d" : body.getOrDefault("period", "30d"));
        return submit(Topics.INSIGHTS_REQUESTS, "GenerateInsights", userId, p, j);
    }

    private ResponseEntity<Map<String, Object>> submit(String topic, String type, String entityId,
                                                       Map<String, Object> payload, JourneyContext j) {
        String requestId = UUID.randomUUID().toString();
        String userId = j.userId() != null ? j.userId() : entityId;
        EventEnvelope cmd = EventEnvelope.command(requestId, type, j.name(), userId, entityId, payload);
        statuses.pending(requestId, type, j.name(), j.id(), entityId);
        try {
            // Wait for the broker ack: a 202 must mean the command is durable.
            publisher.publish(topic, cmd).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            statuses.resolve(requestId, com.demo.workloads.common.events.RequestStatus.FAILED, type + "Rejected",
                    null, "bus unavailable");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5")
                    .body(Map.of("error", "event bus unavailable"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", requestId);
        out.put("status", "PENDING");
        out.put("entityId", entityId);
        out.put("statusUrl", "/api/requests/" + requestId);
        return ResponseEntity.accepted().header("Location", "/api/requests/" + requestId).body(out);
    }
}
