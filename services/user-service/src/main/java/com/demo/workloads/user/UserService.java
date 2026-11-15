package com.demo.workloads.user;

import com.demo.workloads.common.events.EventEnvelope;
import com.demo.workloads.common.idempotency.ProcessedEvents;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class UserService {
    private final UserVendors vendors;
    private final JdbcTemplate jdbc;
    private final ProcessedEvents processed;
    private final TransactionTemplate tx;

    public UserService(UserVendors vendors, JdbcTemplate jdbc, ProcessedEvents processed, TransactionTemplate tx) {
        this.vendors = vendors;
        this.jdbc = jdbc;
        this.processed = processed;
        this.tx = tx;
    }

    /** Returns the outcome payload, or empty when the command was already processed (redelivery). */
    public Optional<Map<String, Object>> handle(EventEnvelope cmd) {
        if (processed.alreadyProcessed(cmd.eventId())) return Optional.empty();
        return switch (cmd.type()) {
            case "CreateUser" -> create(cmd);
            case "UpdateUser" -> update(cmd);
            case "DeleteUser" -> delete(cmd);
            default -> throw new IllegalArgumentException("unknown command " + cmd.type());
        };
    }

    private Optional<Map<String, Object>> create(EventEnvelope cmd) {
        String userId = cmd.entityId();
        String idpId = vendors.createIdpUser(cmd.str("email"), cmd.str("firstName"), cmd.str("lastName"), cmd.idempotencyKey());
        String kyc = vendors.kycCheck(userId, cmd.str("firstName"), cmd.str("lastName"), cmd.str("dateOfBirth"), cmd.idempotencyKey());
        String accountId = "ACC-" + userId;
        return inTx(cmd, () -> {
            jdbc.update("""
                    insert into users(id, email, first_name, last_name, date_of_birth, status, idp_user_id, kyc_status, account_id)
                    values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?) on conflict (id) do nothing""",
                    userId, cmd.str("email"), cmd.str("firstName"), cmd.str("lastName"), cmd.str("dateOfBirth"),
                    idpId, kyc, accountId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("userId", userId);
            out.put("accountId", accountId);
            out.put("idpUserId", idpId);
            out.put("kycStatus", kyc);
            return out;
        });
    }

    private Optional<Map<String, Object>> update(EventEnvelope cmd) {
        String userId = cmd.entityId();
        String idpId = idpId(userId);
        Map<String, Object> changes = new HashMap<>(cmd.payload());
        vendors.updateIdpUser(idpId, changes);
        return inTx(cmd, () -> {
            jdbc.update("""
                    update users set email = coalesce(?, email), first_name = coalesce(?, first_name),
                                     last_name = coalesce(?, last_name), updated_at = now() where id = ?""",
                    cmd.str("email"), cmd.str("firstName"), cmd.str("lastName"), userId);
            return Map.of("userId", userId);
        });
    }

    private Optional<Map<String, Object>> delete(EventEnvelope cmd) {
        String userId = cmd.entityId();
        vendors.deleteIdpUser(idpId(userId));
        return inTx(cmd, () -> {
            jdbc.update("delete from users where id = ?", userId);
            return Map.of("userId", userId);
        });
    }

    private String idpId(String userId) {
        try {
            return jdbc.queryForObject("select idp_user_id from users where id = ?", String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("user " + userId + " not found");
        }
    }

    private Optional<Map<String, Object>> inTx(EventEnvelope cmd, java.util.function.Supplier<Map<String, Object>> work) {
        return tx.execute(s -> processed.markProcessed(cmd.eventId()) ? Optional.of(work.get()) : Optional.empty());
    }

    public Optional<Map<String, Object>> find(String userId) {
        return jdbc.query("""
                select id, email, first_name, last_name, date_of_birth, status, kyc_status, account_id, created_at, updated_at
                from users where id = ?""", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", rs.getString("id"));
            m.put("email", rs.getString("email"));
            m.put("firstName", rs.getString("first_name"));
            m.put("lastName", rs.getString("last_name"));
            m.put("dateOfBirth", rs.getString("date_of_birth"));
            m.put("status", rs.getString("status"));
            m.put("kycStatus", rs.getString("kyc_status"));
            m.put("accountId", rs.getString("account_id"));
            m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            m.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
            return m;
        }, userId).stream().findFirst();
    }
}
