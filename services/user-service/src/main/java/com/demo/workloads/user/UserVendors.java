package com.demo.workloads.user;

import com.demo.workloads.common.vendor.VendorCaller;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Acme Identity (IdP user directory) and KYCheck (identity verification). */
@Component
public class UserVendors {
    private final VendorCaller vendors;

    public UserVendors(VendorCaller vendors) {
        this.vendors = vendors;
    }

    @SuppressWarnings("unchecked")
    public String createIdpUser(String email, String firstName, String lastName, String idempotencyKey) {
        Map<String, Object> res = vendors.call("acme-identity", "create_user", c -> c.post().uri("/users")
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", idempotencyKey)
                .body(Map.of("email", email, "given_name", firstName, "family_name", lastName))
                .retrieve().body(Map.class));
        return String.valueOf(res.get("id"));
    }

    public void updateIdpUser(String idpUserId, Map<String, Object> changes) {
        vendors.call("acme-identity", "update_user", c -> c.patch().uri("/users/{id}", idpUserId)
                .contentType(MediaType.APPLICATION_JSON).body(changes)
                .retrieve().toBodilessEntity());
    }

    public void deleteIdpUser(String idpUserId) {
        vendors.call("acme-identity", "delete_user", c -> c.delete().uri("/users/{id}", idpUserId)
                .retrieve().toBodilessEntity());
    }

    @SuppressWarnings("unchecked")
    public String kycCheck(String userId, String firstName, String lastName, String dateOfBirth, String idempotencyKey) {
        Map<String, Object> res = vendors.call("kycheck", "check", c -> c.post().uri("/checks")
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", idempotencyKey)
                .body(Map.of("reference", userId, "firstName", firstName, "lastName", lastName,
                        "dateOfBirth", dateOfBirth == null ? "1990-01-01" : dateOfBirth))
                .retrieve().body(Map.class));
        return String.valueOf(res.get("result"));
    }
}
