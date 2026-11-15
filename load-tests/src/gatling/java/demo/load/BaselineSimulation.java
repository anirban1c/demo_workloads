package demo.load;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;

/** 50 journeys/s (default) of the weighted journey mix for 10 minutes, after a 30 s warm-up ramp. */
public class BaselineSimulation extends Simulation {
    ScenarioBuilder mixed = scenario("baseline mix").feed(Journeys.users()).exec(Journeys.IDENTIFY).exec(Journeys.mixed());

    {
        setUp(mixed.injectOpen(
                rampUsersPerSec(1).to(Config.TPS).during(Duration.ofSeconds(30)),
                constantUsersPerSec(Config.TPS).during(Duration.ofSeconds(Config.DURATION_S))))
                .protocols(Journeys.protocol())
                .assertions(Slo.standard());
    }
}
