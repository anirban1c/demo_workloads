package com.demo.workloads.insights;

import com.demo.workloads.common.events.EventEnvelope;
import com.demo.workloads.common.idempotency.ProcessedEvents;
import com.demo.workloads.common.vendor.VendorCaller;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * generate_insights: calls InsightAI (LLM analytics SaaS). It is the slowest, most quota-limited vendor, which is
 * why the journey is fully async: the bulkhead (8) + rate limiter (10/s) bound the vendor load, Kafka holds the rest.
 */
@Service
public class InsightsService {
    private final VendorCaller vendors;
    private final JdbcTemplate jdbc;
    private final ProcessedEvents processed;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public InsightsService(VendorCaller vendors, JdbcTemplate jdbc, ProcessedEvents processed,
                           TransactionTemplate tx, ObjectMapper mapper) {
        this.vendors = vendors;
        this.jdbc = jdbc;
        this.processed = processed;
        this.tx = tx;
        this.mapper = mapper;
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> generate(EventEnvelope cmd) {
        if (processed.alreadyProcessed(cmd.eventId())) return Optional.empty();
        String period = Optional.ofNullable(cmd.str("period")).orElse("30d");
        Map<String, Object> res = vendors.call("insight-ai", "generate", c -> c.post().uri("/v1/insights")
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", cmd.idempotencyKey())
                .body(Map.of("subject", cmd.userId(), "period", period, "model", "insight-large"))
                .retrieve().body(Map.class));
        String summary = String.valueOf(res.get("summary"));
        String categories = json(res.getOrDefault("categories", List.of()));
        return tx.execute(s -> {
            if (!processed.markProcessed(cmd.eventId())) return Optional.<Map<String, Object>>empty();
            jdbc.update("""
                    insert into insights(request_id, user_id, period, status, summary, categories)
                    values (?, ?, ?, 'COMPLETED', ?, ?) on conflict (request_id) do nothing""",
                    cmd.requestId(), cmd.userId(), period, summary, categories);
            return Optional.of(Map.of("requestId", cmd.requestId(), "userId", cmd.userId(), "summary", summary));
        });
    }

    public Optional<Map<String, Object>> find(String userId, String requestId) {
        return jdbc.query("select * from insights where user_id = ? and request_id = ?", this::row, userId, requestId)
                .stream().findFirst();
    }

    public List<Map<String, Object>> latest(String userId) {
        return jdbc.query("select * from insights where user_id = ? order by created_at desc limit 5", this::row, userId);
    }

    private Map<String, Object> row(ResultSet rs, int i) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", rs.getString("request_id"));
        m.put("userId", rs.getString("user_id"));
        m.put("period", rs.getString("period"));
        m.put("status", rs.getString("status"));
        m.put("summary", rs.getString("summary"));
        try {
            m.put("categories", mapper.readValue(rs.getString("categories"), List.class));
        } catch (JsonProcessingException e) {
            m.put("categories", List.of());
        }
        m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
        return m;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
