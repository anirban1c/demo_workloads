package com.demo.workloads.funds;

import com.demo.workloads.common.vendor.VendorCaller;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/** Paylane (card/payments SaaS) and Corebank (core-banking ledger SaaS). */
@Component
public class FundsVendors {
    static final String PAYLANE = "paylane";
    static final String COREBANK = "corebank";

    private final VendorCaller vendors;

    public FundsVendors(VendorCaller vendors) {
        this.vendors = vendors;
    }

    @SuppressWarnings("unchecked")
    public String charge(String accountId, BigDecimal amount, String currency, String idempotencyKey) {
        Map<String, Object> res = vendors.call(PAYLANE, "charge", c -> c.post().uri("/v1/charges")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .body(Map.of("amount", amount, "currency", currency, "accountId", accountId))
                .retrieve().body(Map.class));
        return String.valueOf(res.get("id"));
    }

    @SuppressWarnings("unchecked")
    public String postLedger(String accountId, BigDecimal amount, String currency, String reference) {
        Map<String, Object> res = vendors.call(COREBANK, "post_ledger", c -> c.post()
                .uri("/accounts/{id}/ledger", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", reference)
                .body(Map.of("amount", amount, "currency", currency, "type", "CREDIT", "reference", reference))
                .retrieve().body(Map.class));
        return String.valueOf(res.get("entryId"));
    }
}
