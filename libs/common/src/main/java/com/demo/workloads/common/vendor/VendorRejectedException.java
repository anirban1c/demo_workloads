package com.demo.workloads.common.vendor;

/** Business rejection from a vendor (4xx such as card declined / bad credentials). Not retryable, ignored by breakers. */
public class VendorRejectedException extends RuntimeException {
    private final String vendor;
    private final int status;

    public VendorRejectedException(String vendor, int status, String message) {
        super(vendor + " rejected (" + status + "): " + message);
        this.vendor = vendor;
        this.status = status;
    }

    public String vendor() { return vendor; }

    public int status() { return status; }
}
