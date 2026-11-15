package demo.load;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Occasional high vendor latency: the 50/s mix runs for 6 min; between minute 1.5 and 3.5 a chaos scenario makes
 * faultPct% (default 30) of calls to `vendor` (default paylane) take faultDelay ms (default 5000, above the 3 s
 * client timeout). Expected: reads unaffected, async journeys slow down via retry topics / breaker pause, then
 * recover after the fault is cleared. -DfaultPct=100 demonstrates the open circuit + paused consumers.
 */
public class VendorLatencySimulation extends Simulation {
    static final String PREFIX = switch (Config.VENDOR) {
        case "acme-identity" -> "identity";
        case "kycheck" -> "kyc";
        case "relatecrm" -> "crm";
        case "insight-ai" -> "insights-ai";
        default -> Config.VENDOR;
    };

    static String pctRegex(int pct) {
        int n = Math.max(1, Math.min(256, Math.round(pct * 256 / 100f)));
        if (n == 256) return "^.*";
        int full = n / 16, rem = n % 16;
        StringBuilder sb = new StringBuilder("^(");
        if (full > 0) sb.append("[0-").append(Integer.toHexString(full - 1)).append("][0-9a-f]");
        if (rem > 0) sb.append(full > 0 ? "|" : "").append(Integer.toHexString(full)).append("[0-")
                .append(Integer.toHexString(rem - 1)).append("]");
        return sb.append(").*").toString();
    }

    static final String FAULT_STUB = """
            {"priority":1,
             "request":{"method":"ANY","urlPathPattern":"/%s/.*","headers":{"X-Attempt-Id":{"matches":"%s"}}},
             "response":{"proxyBaseUrl":"http://localhost:8080","removeProxyRequestHeaders":["X-Attempt-Id"],
                         "fixedDelayMilliseconds":%d},
             "metadata":{"fault":{"vendor":"%s","kind":"latency","delayMs":%d,"pct":%d}}}"""
            .formatted(PREFIX, pctRegex(Config.FAULT_PCT), Config.FAULT_DELAY_MS, Config.VENDOR,
                    Config.FAULT_DELAY_MS, Config.FAULT_PCT);

    ScenarioBuilder load = scenario("mix under vendor latency").feed(Journeys.users()).exec(Journeys.IDENTIFY)
            .exec(Journeys.mixed());

    ScenarioBuilder chaos = scenario("chaos: " + Config.VENDOR + " latency")
            .pause(Duration.ofSeconds(90))
            .exec(http("chaos inject").post(Config.WIREMOCK + "/__admin/mappings").body(StringBody(FAULT_STUB))
                    .header("Content-Type", "application/json").check(status().is(201))
                    .check(jsonPath("$.id").saveAs("faultId")))
            .pause(Duration.ofSeconds(120))
            .exec(http("chaos clear").delete(Config.WIREMOCK + "/__admin/mappings/#{faultId}").check(status().is(200)));

    {
        setUp(
                load.injectOpen(
                        rampUsersPerSec(1).to(Config.TPS).during(Duration.ofSeconds(20)),
                        constantUsersPerSec(Config.TPS).during(Duration.ofSeconds(360))),
                chaos.injectOpen(atOnceUsers(1)))
                .protocols(Journeys.protocol())
                .assertions(Slo.degraded());
    }
}
