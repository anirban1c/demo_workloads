package com.demo.workloads.gateway.status;

import com.demo.workloads.common.events.RequestStatus;
import com.demo.workloads.gateway.GatewayProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Async request status, one Redis hash per request: req:{requestId}. */
@Component
public class RequestStatusStore {
    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RequestStatusStore(StringRedisTemplate redis, GatewayProperties props) {
        this.redis = redis;
        this.ttl = props.statusTtl();
    }

    public void pending(String requestId, String type, String journey, String journeyId, String entityId) {
        Map<String, String> h = new HashMap<>();
        h.put("requestId", requestId);
        h.put("status", RequestStatus.PENDING.name());
        h.put("type", type);
        h.put("journey", journey);
        h.put("journeyId", journeyId);
        h.put("entityId", entityId);
        h.put("createdAt", Instant.now().toString());
        h.put("updatedAt", h.get("createdAt"));
        write(requestId, h);
    }

    public void resolve(String requestId, RequestStatus status, String eventType, String resultJson, String error) {
        Map<String, String> h = new HashMap<>();
        h.put("status", status.name());
        h.put("outcomeType", eventType);
        h.put("updatedAt", Instant.now().toString());
        if (resultJson != null) h.put("result", resultJson);
        if (error != null) h.put("error", error);
        write(requestId, h);
    }

    public Map<String, String> get(String requestId) {
        Map<Object, Object> raw = redis.opsForHash().entries(key(requestId));
        Map<String, String> out = new HashMap<>();
        raw.forEach((k, v) -> out.put(k.toString(), v.toString()));
        return out;
    }

    private void write(String requestId, Map<String, String> fields) {
        String key = key(requestId);
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, ttl);
    }

    private static String key(String requestId) {
        return "req:" + requestId;
    }
}
