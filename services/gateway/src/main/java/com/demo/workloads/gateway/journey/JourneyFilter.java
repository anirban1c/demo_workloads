package com.demo.workloads.gateway.journey;

import com.demo.workloads.gateway.status.RequestStatusStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Marks every edge request with its user journey:
 * - a "journey &lt;name&gt;" span (child of the agent's HTTP server span) that is the root of the journey's work,
 * - W3C baggage journey.name / journey.id / journey.step / user.id, which the agent propagates over HTTP and Kafka
 *   headers and the baggage-processor extension copies onto every downstream span,
 * - journey.requests / journey.duration metrics.
 * Status polls inherit the original journey's name and id from the request-status store.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JourneyFilter extends OncePerRequestFilter {
    public static final String JOURNEY_ATTR = "journey";

    private final Tracer tracer = GlobalOpenTelemetry.getTracer("demo-workloads.gateway");
    private final MeterRegistry meters;
    private final RequestStatusStore statuses;

    public JourneyFilter(MeterRegistry meters, RequestStatusStore statuses) {
        this.meters = meters;
        this.statuses = statuses;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        JourneyResolver.Resolved j = JourneyResolver.resolve(req.getMethod(), req.getRequestURI(), req.getHeader("X-User-Id"));
        String journeyName = j.name();
        String journeyId = UUID.randomUUID().toString();
        if (journeyName.equals("request_status")) {
            String requestId = req.getRequestURI().substring("/api/requests/".length());
            Map<String, String> s = statuses.get(requestId);
            if (s.containsKey("journey")) {
                journeyName = s.get("journey");
                journeyId = s.getOrDefault("journeyId", journeyId);
            }
        }
        String step = j.step();

        Span span = tracer.spanBuilder("journey " + journeyName)
                .setAttribute("journey.name", journeyName)
                .setAttribute("journey.id", journeyId)
                .setAttribute("journey.step", step)
                .setAttribute("user.id", j.userId() == null ? "anonymous" : j.userId())
                .startSpan();
        Span.current().setAttribute("journey.name", journeyName); // also tag the HTTP server span
        Baggage baggage = Baggage.current().toBuilder()
                .put("journey.name", journeyName)
                .put("journey.id", journeyId)
                .put("journey.step", step)
                .put("user.id", j.userId() == null ? "anonymous" : j.userId())
                .build();
        req.setAttribute(JOURNEY_ATTR, new JourneyContext(journeyName, journeyId, step, j.userId()));
        res.setHeader("X-Journey-Id", journeyId);

        Timer.Sample sample = Timer.start(meters);
        String outcome = "error";
        try (Scope ignored = Context.current().with(span).with(baggage).makeCurrent()) {
            chain.doFilter(req, res);
            int code = res.getStatus();
            outcome = code == 429 ? "throttled" : code >= 500 ? "error" : code >= 400 ? "client_error" : "success";
            span.setAttribute("http.response.status_code", code);
            if (code >= 500) span.setStatus(StatusCode.ERROR);
        } catch (IOException | ServletException | RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.setAttribute("journey.outcome", outcome);
            span.end();
            sample.stop(Timer.builder("journey.duration").description("Edge latency per journey step")
                    .tag("journey", journeyName).tag("step", step).tag("outcome", outcome)
                    .publishPercentileHistogram().register(meters));
            meters.counter("journey.requests", "journey", journeyName, "step", step, "outcome", outcome).increment();
        }
    }

    public record JourneyContext(String name, String id, String step, String userId) {}
}
