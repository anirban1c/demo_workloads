package com.demo.workloads.gateway.api;

import com.demo.workloads.gateway.GatewayProperties;
import com.demo.workloads.gateway.status.RequestStatusStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;

/** Synchronous paths: login and the reads, proxied to the owning service; plus async request status. */
@RestController
@RequestMapping("/api")
public class QueryController {
    private final RestClient http;
    private final GatewayProperties.Services svc;
    private final RequestStatusStore statuses;
    private final ObjectMapper mapper;

    public QueryController(RestClient.Builder builder, GatewayProperties props, RequestStatusStore statuses,
                           ObjectMapper mapper) {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build());
        f.setReadTimeout(props.downstreamTimeout());
        this.http = builder.requestFactory(f).build();
        this.svc = props.services();
        this.statuses = statuses;
        this.mapper = mapper;
    }

    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody String body) {
        return forward(() -> http.post().uri(svc.login() + "/login").contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @GetMapping("/accounts/{accountId}/balances")
    public ResponseEntity<String> balances(@PathVariable String accountId) {
        return forward(() -> http.get().uri(svc.balance() + "/accounts/{id}/balances", accountId));
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<String> user(@PathVariable String userId) {
        return forward(() -> http.get().uri(svc.user() + "/users/{id}", userId));
    }

    @GetMapping("/users/{userId}/profile")
    public ResponseEntity<String> profile(@PathVariable String userId) {
        return forward(() -> http.get().uri(svc.profile() + "/users/{id}/profile", userId));
    }

    @GetMapping("/users/{userId}/insights")
    public ResponseEntity<String> insights(@PathVariable String userId) {
        return forward(() -> http.get().uri(svc.insights() + "/users/{id}/insights", userId));
    }

    @GetMapping("/users/{userId}/insights/{requestId}")
    public ResponseEntity<String> insight(@PathVariable String userId, @PathVariable String requestId) {
        return forward(() -> http.get().uri(svc.insights() + "/users/{id}/insights/{rid}", userId, requestId));
    }

    @GetMapping("/requests/{requestId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String requestId) throws Exception {
        Map<String, String> s = statuses.get(requestId);
        if (s.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, Object> out = new LinkedHashMap<>(s);
        if (s.containsKey("result")) out.put("result", mapper.readValue(s.get("result"), Map.class));
        return ResponseEntity.ok(out);
    }

    private ResponseEntity<String> forward(java.util.function.Supplier<RestClient.RequestHeadersSpec<?>> call) {
        try {
            return call.get().exchange((req, res) -> {
                HttpStatusCode code = res.getStatusCode();
                String body = new String(res.getBody().readAllBytes());
                ResponseEntity.BodyBuilder b = ResponseEntity.status(code).contentType(MediaType.APPLICATION_JSON);
                String retryAfter = res.getHeaders().getFirst("Retry-After");
                if (retryAfter != null) b.header("Retry-After", retryAfter);
                return b.body(body);
            });
        } catch (ResourceAccessException e) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"downstream timeout\"}");
        }
    }
}
