package com.demo.workloads.login;

import com.demo.workloads.common.journey.Journey;
import com.demo.workloads.common.vendor.VendorCaller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** login: synchronous token exchange with Acme Identity (OAuth2 password grant against the IdP SaaS). */
@RestController
public class LoginController {
    private final VendorCaller vendors;

    public LoginController(VendorCaller vendors) {
        this.vendors = vendors;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    @PostMapping("/login")
    @SuppressWarnings("unchecked")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req) {
        Journey.tagCurrentSpan();
        Map<String, Object> token = vendors.call("acme-identity", "token", c -> c.post().uri("/oauth2/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("grant_type", "password", "username", req.username(), "password", req.password(),
                        "client_id", "demo-workloads"))
                .retrieve().body(Map.class));
        return Map.of("accessToken", token.get("access_token"), "tokenType", "Bearer",
                "expiresIn", token.getOrDefault("expires_in", 3600));
    }
}
