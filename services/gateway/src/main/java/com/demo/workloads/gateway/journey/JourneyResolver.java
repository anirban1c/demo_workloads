package com.demo.workloads.gateway.journey;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps an edge request onto one of the user journeys (and the CRUD step for the CRUD journeys). */
public final class JourneyResolver {
    public record Resolved(String name, String step, String userId) {}

    private static final Pattern ACCOUNT = Pattern.compile("^/api/accounts/([^/]+)/(balances|funds)$");
    private static final Pattern USER = Pattern.compile("^/api/users(?:/([^/]+))?(/profile|/insights(?:/[^/]+)?)?$");

    private JourneyResolver() {}

    public static Resolved resolve(String method, String path, String userHeader) {
        if (path.equals("/api/login")) return new Resolved("login", "authenticate", userHeader);
        if (path.startsWith("/api/requests/")) return new Resolved("request_status", "poll", userHeader);
        Matcher a = ACCOUNT.matcher(path);
        if (a.matches()) {
            String user = userHeader != null ? userHeader : a.group(1).replaceFirst("^ACC-", "");
            return a.group(2).equals("balances")
                    ? new Resolved("get_balances", "read", user)
                    : new Resolved("add_funds", "submit", user);
        }
        Matcher u = USER.matcher(path);
        if (u.matches()) {
            String user = u.group(1) != null ? u.group(1) : userHeader;
            String sub = u.group(2);
            if (sub == null) return new Resolved("user_crud", crudStep(method), user);
            if (sub.equals("/profile")) return new Resolved("user_profile_crud", crudStep(method), user);
            return new Resolved("generate_insights", method.equals("POST") ? "submit" : "read", user);
        }
        return new Resolved("other", method.toLowerCase(), userHeader);
    }

    private static String crudStep(String method) {
        return switch (method) {
            case "POST" -> "create";
            case "PUT", "PATCH" -> "update";
            case "DELETE" -> "delete";
            default -> "read";
        };
    }
}
