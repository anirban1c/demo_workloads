package com.demo.workloads.profile;

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

import java.util.Map;

@RestController
public class ProfileEndpoints {
    private final ProfileService profiles;
    private final EventPublisher publisher;

    public ProfileEndpoints(ProfileService profiles, EventPublisher publisher) {
        this.profiles = profiles;
        this.publisher = publisher;
    }

    @GetMapping("/users/{userId}/profile")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String userId) {
        Journey.tagCurrentSpan();
        return ResponseEntity.of(profiles.find(userId));
    }

    @KafkaListener(id = "profile-commands", topics = Topics.PROFILE_COMMANDS)
    public void onCommand(String json) {
        EventEnvelope cmd = publisher.parse(json);
        Journey.tagCurrentSpan();
        String done = "DeleteProfile".equals(cmd.type()) ? "ProfileDeleted" : "ProfileUpserted";
        try {
            profiles.handle(cmd).ifPresent(r -> publisher.publishSync(Topics.PROFILE_EVENTS, cmd.completed(done, r)));
        } catch (VendorRejectedException | IllegalArgumentException e) {
            publisher.publishSync(Topics.PROFILE_EVENTS, cmd.failed(cmd.type() + "Failed", e.getMessage()));
        }
    }
}
