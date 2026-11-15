package demo.load;

import io.gatling.javaapi.core.Simulation;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;

/** Long run at the baseline rate (default 1 h) to surface leaks, lag creep and status-store growth. */
public class SoakSimulation extends Simulation {
    {
        int seconds = Config.intProp("duration", 3600);
        setUp(scenario("soak mix").feed(Journeys.users()).exec(Journeys.IDENTIFY).exec(Journeys.mixed())
                .injectOpen(
                        rampUsersPerSec(1).to(Config.TPS).during(Duration.ofSeconds(60)),
                        constantUsersPerSec(Config.TPS).during(Duration.ofSeconds(seconds))))
                .protocols(Journeys.protocol())
                .assertions(Slo.standard());
    }
}
