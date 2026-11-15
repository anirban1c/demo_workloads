package com.demo.workloads.user;

import com.demo.workloads.common.events.EventEnvelope;
import com.demo.workloads.common.events.EventPublisher;
import com.demo.workloads.common.events.Topics;
import com.demo.workloads.common.journey.Journey;
import com.demo.workloads.common.vendor.VendorRejectedException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class UserCommandListener {
    private final UserService users;
    private final EventPublisher publisher;

    public UserCommandListener(UserService users, EventPublisher publisher) {
        this.users = users;
        this.publisher = publisher;
    }

    @KafkaListener(id = "user-commands", topics = Topics.USER_COMMANDS)
    public void onCommand(String json) {
        EventEnvelope cmd = publisher.parse(json);
        Journey.tagCurrentSpan();
        String done = switch (cmd.type()) {
            case "CreateUser" -> "UserCreated";
            case "UpdateUser" -> "UserUpdated";
            case "DeleteUser" -> "UserDeleted";
            default -> cmd.type() + "Done";
        };
        try {
            users.handle(cmd).ifPresent(r -> publisher.publishSync(Topics.USER_EVENTS, cmd.completed(done, r)));
        } catch (VendorRejectedException | IllegalArgumentException e) {
            publisher.publishSync(Topics.USER_EVENTS, cmd.failed(cmd.type() + "Failed", e.getMessage()));
        }
    }
}
