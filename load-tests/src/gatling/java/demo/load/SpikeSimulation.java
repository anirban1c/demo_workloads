package demo.load;

import io.gatling.javaapi.core.Simulation;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;

/**
 * Burst handling: 50/s baseline, a 60 s burst at spikeTps (default 300), then back to 50/s for 3 min while the
 * consumers drain the backlog. Expected: writes are accepted (202) or shed (429) - never 5xx - and reads stay fast.
 */
public class SpikeSimulation extends Simulation {
    {
        setUp(scenario("spike mix").feed(Journeys.users()).exec(Journeys.IDENTIFY).exec(Journeys.mixed())
                .injectOpen(
                        rampUsersPerSec(1).to(Config.TPS).during(Duration.ofSeconds(20)),
                        constantUsersPerSec(Config.TPS).during(Duration.ofSeconds(120)),
                        rampUsersPerSec(Config.TPS).to(Config.SPIKE_TPS).during(Duration.ofSeconds(5)),
                        constantUsersPerSec(Config.SPIKE_TPS).during(Duration.ofSeconds(60)),
                        rampUsersPerSec(Config.SPIKE_TPS).to(Config.TPS).during(Duration.ofSeconds(5)),
                        constantUsersPerSec(Config.TPS).during(Duration.ofSeconds(180))))
                .protocols(Journeys.protocol())
                .assertions(Slo.degraded());
    }
}
