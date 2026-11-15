package demo.load;

import io.gatling.javaapi.core.Assertion;

import java.util.List;

import static io.gatling.javaapi.core.CoreDsl.*;

/** Shared SLO assertions, per journey step (names match Journeys request / group names). */
public final class Slo {
    private Slo() {}

    public static List<Assertion> standard() {
        return List.of(
                global().failedRequests().percent().lt(1.0),
                details("login authenticate").responseTime().percentile(95).lt(800),
                details("get_balances read").responseTime().percentile(95).lt(300),
                details("add_funds e2e", "add_funds submit").responseTime().percentile(95).lt(300),
                details("add_funds e2e").responseTime().percentile(95).lt(5000),
                details("add_funds e2e", "add_funds outcome").successfulRequests().percent().gt(99.0),
                details("generate_insights e2e").responseTime().percentile(95).lt(15000),
                details("generate_insights e2e", "generate_insights outcome").successfulRequests().percent().gt(99.0),
                details("user_crud e2e", "user_crud outcome").successfulRequests().percent().gt(99.0),
                details("user_profile_crud e2e", "user_profile_crud outcome").successfulRequests().percent().gt(99.0),
                details("user_crud read").responseTime().percentile(95).lt(300),
                details("user_profile_crud read").responseTime().percentile(95).lt(300));
    }

    /** Under faults/spikes: reads stay fast and nothing 5xx's; async journeys may be slow but must resolve. */
    public static List<Assertion> degraded() {
        return List.of(
                global().failedRequests().percent().lt(5.0),
                details("get_balances read").responseTime().percentile(95).lt(300),
                details("get_balances read").failedRequests().percent().lt(1.0),
                details("user_crud read").failedRequests().percent().lt(1.0));
    }
}
