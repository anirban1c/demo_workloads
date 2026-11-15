package demo.load;

import io.gatling.javaapi.core.Simulation;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;

/** Step load from 10 journeys/s up to maxTps (default 150) in +10/s steps of 60 s to find the knee. */
public class RampSimulation extends Simulation {
    {
        int steps = (int) Math.ceil((Config.MAX_TPS - 10) / 10.0) + 1;
        setUp(scenario("ramp mix").feed(Journeys.users()).exec(Journeys.IDENTIFY).exec(Journeys.mixed())
                .injectOpen(incrementUsersPerSec(10)
                        .times(steps)
                        .eachLevelLasting(Duration.ofSeconds(Config.intProp("stepSeconds", 60)))
                        .separatedByRampsLasting(Duration.ofSeconds(10))
                        .startingFrom(10)))
                .protocols(Journeys.protocol())
                .assertions(global().failedRequests().percent().lt(5.0));
    }
}
