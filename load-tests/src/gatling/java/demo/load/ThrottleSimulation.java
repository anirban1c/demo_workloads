package demo.load;

import io.gatling.javaapi.core.Simulation;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Edge throttling: one noisy client sends 60 req/s (3x its 20 req/s quota) while a polite client sends 5 req/s.
 * Expected: roughly two thirds of the noisy client's requests get 429 + Retry-After; the polite client is unaffected.
 */
public class ThrottleSimulation extends Simulation {
    {
        var noisy = scenario("noisy client").feed(Journeys.users())
                .exec(session -> session.set("clientId", "noisy-client"))
                .exec(http("throttle noisy").get("/api/accounts/#{accountId}/balances")
                        .check(status().in(200, 429).saveAs("code"))
                        .check(status().is(200)));
        var polite = scenario("polite client").feed(Journeys.users())
                .exec(session -> session.set("clientId", "polite-client"))
                .exec(http("throttle polite").get("/api/accounts/#{accountId}/balances")
                        .check(status().is(200)));
        int seconds = Config.intProp("duration", 60);
        setUp(
                noisy.injectOpen(constantUsersPerSec(60).during(Duration.ofSeconds(seconds))),
                polite.injectOpen(constantUsersPerSec(5).during(Duration.ofSeconds(seconds))))
                .protocols(Journeys.protocol())
                .assertions(
                        details("throttle noisy").failedRequests().percent().between(40.0, 80.0),
                        details("throttle polite").failedRequests().percent().lt(1.0));
    }
}
