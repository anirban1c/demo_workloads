package com.demo.workloads.common.vendor;

/** Transient vendor failure (timeout, 5xx, circuit open, throttled locally). Retryable, counts against the breaker. */
public class VendorUnavailableException extends RuntimeException {
    private final String vendor;

    public VendorUnavailableException(String vendor, String message, Throwable cause) {
        super(vendor + ": " + message, cause);
        this.vendor = vendor;
    }

    public String vendor() { return vendor; }
}
