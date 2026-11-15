package com.demo.workloads.profile;

import com.demo.workloads.common.events.EventEnvelope;
import com.demo.workloads.common.idempotency.ProcessedEvents;
import com.demo.workloads.common.vendor.VendorCaller;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** user_profile CRUD. Profiles are mirrored to RelateCRM (customer-relationship SaaS) as contacts. */
@Service
public class ProfileService {
    private static final String CRM = "relatecrm";
    private static final String[] FIELDS = {"displayName", "phone", "addressLine", "city", "postcode", "country", "marketingOptIn"};

    private final VendorCaller vendors;
    private final JdbcTemplate jdbc;
    private final ProcessedEvents processed;
    private final TransactionTemplate tx;

    public ProfileService(VendorCaller vendors, JdbcTemplate jdbc, ProcessedEvents processed, TransactionTemplate tx) {
        this.vendors = vendors;
        this.jdbc = jdbc;
        this.processed = processed;
        this.tx = tx;
    }

    public Optional<Map<String, Object>> handle(EventEnvelope cmd) {
        if (processed.alreadyProcessed(cmd.eventId())) return Optional.empty();
        return switch (cmd.type()) {
            case "UpsertProfile" -> upsert(cmd);
            case "DeleteProfile" -> delete(cmd);
            default -> throw new IllegalArgumentException("unknown command " + cmd.type());
        };
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> upsert(EventEnvelope cmd) {
        String userId = cmd.entityId();
        Map<String, Object> contact = new LinkedHashMap<>(cmd.payload());
        contact.put("externalRef", userId);
        String crmId = crmId(userId);
        if (crmId == null) {
            Map<String, Object> res = vendors.call(CRM, "create_contact", c -> c.post().uri("/contacts")
                    .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", cmd.idempotencyKey())
                    .body(contact).retrieve().body(Map.class));
            crmId = String.valueOf(res.get("id"));
        } else {
            String id = crmId;
            vendors.call(CRM, "update_contact", c -> c.put().uri("/contacts/{id}", id)
                    .contentType(MediaType.APPLICATION_JSON).body(contact).retrieve().toBodilessEntity());
        }
        String finalCrmId = crmId;
        return tx.execute(s -> {
            if (!processed.markProcessed(cmd.eventId())) return Optional.<Map<String, Object>>empty();
            jdbc.update("""
                    insert into profiles(user_id, display_name, phone, address_line, city, postcode, country,
                                         marketing_opt_in, crm_contact_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (user_id) do update set
                      display_name = excluded.display_name, phone = excluded.phone,
                      address_line = excluded.address_line, city = excluded.city, postcode = excluded.postcode,
                      country = excluded.country, marketing_opt_in = excluded.marketing_opt_in,
                      crm_contact_id = excluded.crm_contact_id, updated_at = now()""",
                    userId, cmd.str("displayName"), cmd.str("phone"), cmd.str("addressLine"), cmd.str("city"),
                    cmd.str("postcode"), cmd.str("country"), Boolean.parseBoolean(cmd.str("marketingOptIn")), finalCrmId);
            return Optional.of(Map.of("userId", userId, "crmContactId", finalCrmId));
        });
    }

    private Optional<Map<String, Object>> delete(EventEnvelope cmd) {
        String userId = cmd.entityId();
        String crmId = crmId(userId);
        if (crmId != null) {
            vendors.call(CRM, "delete_contact", c -> c.delete().uri("/contacts/{id}", crmId).retrieve().toBodilessEntity());
        }
        return tx.execute(s -> {
            if (!processed.markProcessed(cmd.eventId())) return Optional.<Map<String, Object>>empty();
            jdbc.update("delete from profiles where user_id = ?", userId);
            return Optional.of(Map.of("userId", userId));
        });
    }

    private String crmId(String userId) {
        return jdbc.query("select crm_contact_id from profiles where user_id = ?",
                (rs, i) -> rs.getString(1), userId).stream().findFirst().orElse(null);
    }

    public Optional<Map<String, Object>> find(String userId) {
        return jdbc.query("select * from profiles where user_id = ?", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", rs.getString("user_id"));
            m.put("displayName", rs.getString("display_name"));
            m.put("phone", rs.getString("phone"));
            m.put("addressLine", rs.getString("address_line"));
            m.put("city", rs.getString("city"));
            m.put("postcode", rs.getString("postcode"));
            m.put("country", rs.getString("country"));
            m.put("marketingOptIn", rs.getBoolean("marketing_opt_in"));
            m.put("crmContactId", rs.getString("crm_contact_id"));
            m.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
            return m;
        }, userId).stream().findFirst();
    }
}
