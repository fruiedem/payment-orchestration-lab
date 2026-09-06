package lab.payment.orchestration.observability;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestLatencyFilter extends OncePerRequestFilter {
    private final MeterRegistry registry;

    public RequestLatencyFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            if (request.getRequestURI().equals("/api/orders")) {
                registry.timer("payment.request.latency", "method", request.getMethod(),
                                "status", Integer.toString(response.getStatus()))
                        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            }
        }
    }
}
