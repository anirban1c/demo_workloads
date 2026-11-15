package com.demo.workloads.balance;

import com.demo.workloads.common.journey.Journey;
import com.demo.workloads.common.vendor.VendorCaller;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
public class BalanceController {
    private final JdbcTemplate jdbc;
    private final VendorCaller vendors;

    public BalanceController(JdbcTemplate jdbc, VendorCaller vendors) {
        this.jdbc = jdbc;
        this.vendors = vendors;
    }

    /** Read model first; on a miss fall back to the core-banking vendor (behind its circuit breaker) and cache. */
    @GetMapping("/accounts/{accountId}/balances")
    public Map<String, Object> balances(@PathVariable String accountId) {
        Journey.tagCurrentSpan();
        try {
            return jdbc.queryForObject(
                    "select account_id, available, currency, source, updated_at from balances where account_id = ?",
                    (rs, i) -> Map.of(
                            "accountId", rs.getString("account_id"),
                            "available", rs.getBigDecimal("available"),
                            "currency", rs.getString("currency"),
                            "source", rs.getString("source"),
                            "asOf", rs.getTimestamp("updated_at").toInstant().toString()),
                    accountId);
        } catch (EmptyResultDataAccessException miss) {
            return fromCorebank(accountId);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromCorebank(String accountId) {
        Map<String, Object> res = vendors.call("corebank", "get_balance",
                c -> c.get().uri("/accounts/{id}/balance", accountId).retrieve().body(Map.class));
        BigDecimal available = new BigDecimal(String.valueOf(res.get("available")));
        String currency = String.valueOf(res.getOrDefault("currency", "GBP"));
        jdbc.update("""
                insert into balances(account_id, available, currency, source) values (?, ?, ?, 'corebank')
                on conflict do nothing""", accountId, available, currency);
        return Map.of("accountId", accountId, "available", available, "currency", currency, "source", "corebank");
    }
}
