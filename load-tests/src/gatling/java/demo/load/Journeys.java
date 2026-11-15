package demo.load;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import net.datafaker.Faker;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * The six user journeys as Gatling chains. Request names are "<journey> <step>" so the Gatling report, the gateway
 * journey.* metrics and the journey.name trace attribute line up. Async journeys are wrapped in a group whose time
 * is the end-to-end latency (202 accept -> terminal status).
 */
public final class Journeys {
    private static final Faker FAKER = new Faker();

    private Journeys() {}

    public static HttpProtocolBuilder protocol() {
        return http.baseUrl(Config.GATEWAY)
                .contentTypeHeader("application/json")
                .acceptHeader("application/json")
                .header("X-Client-Id", "#{clientId}")
                .header("X-User-Id", "#{userId}")
                .shareConnections()
                .disableCaching();
    }

    /** Seeded users (make seed). Each virtual user gets its own client id so per-client throttling stays per user. */
    public static FeederBuilder<String> users() {
        try {
            return csv("users.csv").random();
        } catch (RuntimeException e) {
            throw new IllegalStateException("users.csv not found: run `make seed` first", e);
        }
    }

    /** Unique client id per virtual user (the gateway rate-limits per X-Client-Id). */
    public static final ChainBuilder IDENTIFY = exec(session -> session.set("clientId", "vu-" + session.userId()));

    // ------------------------------------------------------------------ sync journeys
    public static final ChainBuilder LOGIN = exec(
            http("login authenticate").post("/api/login")
                    .body(StringBody("{\"username\":\"#{email}\",\"password\":\"#{password}\"}"))
                    .check(status().in(200, 429))
                    .check(status().saveAs("httpStatus")));

    public static final ChainBuilder GET_BALANCES = exec(
            http("get_balances read").get("/api/accounts/#{accountId}/balances")
                    .check(status().in(200, 429)));

    // ------------------------------------------------------------------ async journeys
    public static final ChainBuilder ADD_FUNDS = exec(session -> session.set("amount",
            String.format("%.2f", ThreadLocalRandom.current().nextDouble(1, 500))))
            .group("add_funds e2e").on(
                    submit("add_funds submit", http("add_funds submit").post("/api/accounts/#{accountId}/funds")
                            .body(StringBody("{\"amount\":#{amount},\"currency\":\"GBP\"}"))),
                    awaitOutcome("add_funds"));

    public static final ChainBuilder GENERATE_INSIGHTS = group("generate_insights e2e").on(
            submit("generate_insights submit", http("generate_insights submit").post("/api/users/#{userId}/insights")
                    .body(StringBody("{\"period\":\"30d\"}"))),
            awaitOutcome("generate_insights"),
            doIf(Journeys::completed).then(
                    exec(http("generate_insights read").get("/api/users/#{userId}/insights/#{requestId}")
                            .check(status().is(200)))));

    /** user_profile CRUD: 50% read, 45% upsert, 5% delete. */
    public static final ChainBuilder PROFILE_CRUD = randomSwitch().on(
            percent(50).then(exec(http("user_profile_crud read").get("/api/users/#{userId}/profile")
                    .check(status().in(200, 404, 429)))),
            percent(45).then(exec(session -> session.set("profile", profileJson()))
                    .group("user_profile_crud e2e").on(
                            submit("user_profile_crud update", http("user_profile_crud update")
                                    .put("/api/users/#{userId}/profile").body(StringBody("#{profile}"))),
                            awaitOutcome("user_profile_crud"))),
            percent(5).then(group("user_profile_crud e2e").on(
                    submit("user_profile_crud delete", http("user_profile_crud delete")
                            .delete("/api/users/#{userId}/profile")),
                    awaitOutcome("user_profile_crud"))));

    /**
     * user CRUD: 40% read, 30% update, 20% create, 10% create-then-delete. Seeded users are never deleted, so the
     * feeder stays valid; deletes run against users created in the same journey.
     */
    public static final ChainBuilder USER_CRUD = randomSwitch().on(
            percent(40).then(exec(http("user_crud read").get("/api/users/#{userId}").check(status().in(200, 429)))),
            percent(30).then(exec(session -> session.set("lastNameNew", FAKER.name().lastName()))
                    .group("user_crud e2e").on(
                            submit("user_crud update", http("user_crud update").put("/api/users/#{userId}")
                                    .body(StringBody("{\"lastName\":\"#{lastNameNew}\"}"))),
                            awaitOutcome("user_crud"))),
            percent(20).then(createUser()),
            percent(10).then(createUser().doIf(Journeys::completed).then(
                    group("user_crud e2e").on(
                            submit("user_crud delete", http("user_crud delete").delete("/api/users/#{newUserId}")),
                            awaitOutcome("user_crud")))));

    private static ChainBuilder createUser() {
        return exec(session -> session.set("newUser", newUserJson()))
                .group("user_crud e2e").on(
                        submit("user_crud create", http("user_crud create").post("/api/users")
                                .body(StringBody("#{newUser}"))
                                .check(jsonPath("$.entityId").optional().saveAs("newUserId"))),
                        awaitOutcome("user_crud"));
    }

    /** The 50 TPS mix: login 20, balances 30, add_funds 15, profile 15, user 10, insights 10. */
    public static ChainBuilder mixed() {
        return randomSwitch().on(
                percent(20).then(LOGIN),
                percent(30).then(GET_BALANCES),
                percent(15).then(ADD_FUNDS),
                percent(15).then(PROFILE_CRUD),
                percent(10).then(USER_CRUD),
                percent(10).then(GENERATE_INSIGHTS));
    }

    public static ChainBuilder byName(String journey) {
        return switch (journey) {
            case "login" -> LOGIN;
            case "get_balances" -> GET_BALANCES;
            case "add_funds" -> ADD_FUNDS;
            case "user_profile_crud" -> PROFILE_CRUD;
            case "user_crud" -> USER_CRUD;
            case "generate_insights" -> GENERATE_INSIGHTS;
            case "mixed" -> mixed();
            default -> throw new IllegalArgumentException("unknown journey " + journey);
        };
    }

    // ------------------------------------------------------------------ async helpers
    private static ChainBuilder submit(String name, io.gatling.javaapi.http.HttpRequestActionBuilder request) {
        return exec(session -> session.remove("requestId").set("status", "NONE"))
                .exec(request.check(status().in(202, 429)).check(jsonPath("$.requestId").optional().saveAs("requestId")));
    }

    /**
     * Poll /api/requests/{id} with backoff until COMPLETED/FAILED (or the poll timeout), then record a synthetic
     * "&lt;journey&gt; outcome" request that fails unless the journey COMPLETED. Throttled submits are skipped.
     */
    private static ChainBuilder awaitOutcome(String journey) {
        return doIf(session -> session.contains("requestId")).then(
                exec(session -> session.set("pollDeadline", System.currentTimeMillis() + Config.POLL_TIMEOUT_S * 1000L)
                        .set("pollDelay", 200L))
                        .asLongAs(session -> "PENDING".equals(session.getString("status")) || "NONE".equals(session.getString("status")))
                        .on(pause(session -> Duration.ofMillis(session.getLong("pollDelay")))
                                // exponential backoff (x1.5, capped at 2 s) like a well-behaved client
                                .exec(session -> session.set("pollDelay", Math.min(2000L, session.getLong("pollDelay") * 3 / 2)))
                                .exec(http(journey + " poll").get("/api/requests/#{requestId}")
                                        .check(status().in(200, 404, 429))
                                        .check(jsonPath("$.status").optional().saveAs("status")))
                                .exec(session -> System.currentTimeMillis() > session.getLong("pollDeadline")
                                        ? session.set("status", "TIMEOUT") : session))
                        .exec(http(journey + " outcome").get("/api/requests/#{requestId}")
                                .check(status().is(200))
                                .check(jsonPath("$.status").is("COMPLETED"))));
    }

    private static boolean completed(Session session) {
        return "COMPLETED".equals(session.getString("status"));
    }

    private static String newUserJson() {
        String first = FAKER.name().firstName().replace("\"", "");
        String last = FAKER.name().lastName().replace("\"", "");
        String email = (first + "." + last + "." + UUID.randomUUID().toString().substring(0, 8) + "@example.com")
                .toLowerCase().replaceAll("[^a-z0-9.@]", "");
        return String.format("{\"email\":\"%s\",\"firstName\":\"%s\",\"lastName\":\"%s\",\"dateOfBirth\":\"%s\"}",
                email, first, last, FAKER.timeAndDate().birthday(18, 80));
    }

    private static String profileJson() {
        var a = FAKER.address();
        return String.format("{\"displayName\":\"%s\",\"phone\":\"%s\",\"addressLine\":\"%s\",\"city\":\"%s\","
                        + "\"postcode\":\"%s\",\"country\":\"UK\",\"marketingOptIn\":%s}",
                FAKER.name().fullName().replace("\"", ""), FAKER.phoneNumber().cellPhone(),
                a.streetAddress().replace("\"", ""), a.city().replace("\"", ""), a.zipCode(),
                ThreadLocalRandom.current().nextBoolean());
    }
}
