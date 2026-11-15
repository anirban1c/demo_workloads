package com.demo.workloads.common.events;

public enum RequestStatus {
    PENDING, COMPLETED, FAILED;

    public boolean terminal() { return this != PENDING; }
}
