package com.demo.workloads.common.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Consumer-side dedupe. Each service schema has processed_events(event_id primary key). Call
 * {@link #markProcessed(String)} inside the same transaction as the business write: false means a redelivery.
 */
public class ProcessedEvents {
    private final JdbcTemplate jdbc;

    public ProcessedEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean alreadyProcessed(String eventId) {
        Integer n = jdbc.queryForObject("select count(*) from processed_events where event_id = ?", Integer.class, eventId);
        return n != null && n > 0;
    }

    public boolean markProcessed(String eventId) {
        return jdbc.update("insert into processed_events(event_id) values (?) on conflict do nothing", eventId) == 1;
    }
}
