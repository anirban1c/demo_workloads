package com.demo.workloads.common.journey;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;

/**
 * Journey context travels as W3C baggage (journey.name, journey.id, user.id). The OTel baggage-processor agent
 * extension copies it onto every span; {@link #tagCurrentSpan()} is a belt-and-braces copy for key spans.
 */
public final class Journey {
    public static final String NAME = "journey.name";
    public static final String ID = "journey.id";
    public static final String USER = "user.id";

    private Journey() {}

    public static String name() { return Baggage.current().getEntryValue(NAME); }

    public static String id() { return Baggage.current().getEntryValue(ID); }

    public static void tagCurrentSpan() {
        Span span = Span.current();
        Baggage.current().forEach((k, v) -> {
            if (k.startsWith("journey.") || k.equals(USER)) span.setAttribute(k, v.getValue());
        });
    }
}
