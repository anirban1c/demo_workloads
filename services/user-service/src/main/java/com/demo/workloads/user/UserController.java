package com.demo.workloads.user;

import com.demo.workloads.common.journey.Journey;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class UserController {
    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String userId) {
        Journey.tagCurrentSpan();
        return ResponseEntity.of(users.find(userId));
    }
}
