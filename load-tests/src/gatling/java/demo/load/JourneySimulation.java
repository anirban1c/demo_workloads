package demo.load;

import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.Simulation;

import java.time.Duration;
import java.util.List;

import static io.gatling.javaapi.core.CoreDsl.*;

/**
 * Any single journey (or "mixed") at a variable rate:
 *   -Djourney=add_funds -Dtps=20 -Dduration=120 -Dprofile=constant|ramp|spike|wave
 */
public class JourneySimulation extends Simulation {
    static List<OpenInjectionStep> profile(String name, double tps, int seconds) {
        Duration d = Duration.ofSeconds(seconds);
        return switch (name) {
            case "ramp" -> List.of(rampUsersPerSec(1).to(tps).during(d));
            case "spike" -> List.of(
                    constantUsersPerSec(tps).during(d.dividedBy(3)),
                    constantUsersPerSec(tps * 5).during(Duration.ofSeconds(30)),
                    constantUsersPerSec(tps).during(d.dividedBy(3)));
            case "wave" -> List.of(
                    rampUsersPerSec(tps / 4).to(tps).during(d.dividedBy(4)),
                    rampUsersPerSec(tps).to(tps / 4).during(d.dividedBy(4)),
                    rampUsersPerSec(tps / 4).to(tps).during(d.dividedBy(4)),
                    rampUsersPerSec(tps).to(tps / 4).during(d.dividedBy(4)));
            default -> List.of(constantUsersPerSec(tps).during(d));
        };
    }

    {
        String profile = System.getProperty("profile", "constant");
        int seconds = Config.intProp("duration", 120);
        setUp(scenario(Config.JOURNEY + " (" + profile + ")").feed(Journeys.users()).exec(Journeys.IDENTIFY)
                .exec(Journeys.byName(Config.JOURNEY))
                .injectOpen(profile(profile, Config.TPS, seconds)))
                .protocols(Journeys.protocol())
                .assertions(global().failedRequests().percent().lt(2.0));
    }
}
