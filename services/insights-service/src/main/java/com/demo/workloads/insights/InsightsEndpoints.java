package com.demo.workloads.insights;

import com.demo.workloads.common.events.EventEnvelope;
import com.demo.workloads.common.events.EventPublisher;
import com.demo.workloads.common.events.Topics;
import com.demo.workloads.common.journey.Journey;
import com.demo.workloads.common.vendor.VendorRejectedException;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class InsightsEndpoints {
    private final InsightsService insights;
    private final EventPublisher publisher;

    public InsightsEndpoints(InsightsService insights, EventPublisher publisher) {
        this.insights = insights;
        this.publisher = publisher;
    }

    @GetMapping("/users/{userId}/insights/{requestId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String userId, @PathVariable String requestId) {
        Journey.tagCurrentSpan();
        return ResponseEntity.of(insights.find(userId, requestId));
    }

    @GetMapping("/users/{userId}/insights")
    public List<Map<String, Object>> latest(@PathVariable String userId) {
        Journey.tagCurrentSpan();
        return insights.latest(userId);
    }

    @KafkaListener(id = "insights-requests", topics = Topics.INSIGHTS_REQUESTS)
    public void onRequest(String json) {
        EventEnvelope cmd = publisher.parse(json);
        Journey.tagCurrentSpan();
        try {
            insights.generate(cmd).ifPresent(r ->
                    publisher.publishSync(Topics.INSIGHTS_EVENTS, cmd.completed("InsightsGenerated", r)));
        } catch (VendorRejectedException e) {
            publisher.publishSync(Topics.INSIGHTS_EVENTS, cmd.failed("GenerateInsightsFailed", e.getMessage()));
        }
    }
}
