package com.demo.workloads.common.events;

import java.util.List;

/** Topic catalogue. Commands flow gateway -> service; events flow service -> gateway / projections. */
public final class Topics {
    public static final String FUNDS_COMMANDS = "funds.commands";
    public static final String FUNDS_EVENTS = "funds.events";
    public static final String USER_COMMANDS = "user.commands";
    public static final String USER_EVENTS = "user.events";
    public static final String PROFILE_COMMANDS = "profile.commands";
    public static final String PROFILE_EVENTS = "profile.events";
    public static final String INSIGHTS_REQUESTS = "insights.requests";
    public static final String INSIGHTS_EVENTS = "insights.events";

    public static final List<String> ALL = List.of(
            FUNDS_COMMANDS, FUNDS_EVENTS, USER_COMMANDS, USER_EVENTS,
            PROFILE_COMMANDS, PROFILE_EVENTS, INSIGHTS_REQUESTS, INSIGHTS_EVENTS);

    public static final List<String> EVENT_TOPICS = List.of(FUNDS_EVENTS, USER_EVENTS, PROFILE_EVENTS, INSIGHTS_EVENTS);

    public static final List<String> COMMAND_TOPICS = List.of(FUNDS_COMMANDS, USER_COMMANDS, PROFILE_COMMANDS, INSIGHTS_REQUESTS);

    /** The outcome topic for a command topic (used to publish FAILED events from the dead-letter handler). */
    public static String eventsFor(String commandTopic) {
        String base = commandTopic.substring(0, commandTopic.indexOf('.'));
        return base + ".events";
    }

    private Topics() {}
}
