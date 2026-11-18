package com.vivekpatel.payments.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes the actuator read-only: anything but {@code GET} or {@code HEAD} under {@code /actuator}
 * gets {@code 405}.
 *
 * <p>Exposing {@code circuitbreakers} for the demo also exposed its <i>write</i> operation: an
 * unauthenticated {@code POST /actuator/circuitbreakers/processor} with {@code FORCE_OPEN} refused
 * every payment, and {@code DISABLE} switched the breaker off. Found on Day 18, by trying it. Spring
 * Boot 3.3 has no per-endpoint read-only setting ({@code management.endpoint.<id>.access} arrives in
 * 3.4), so this closes every write until authentication exists (Phase 6), which is where a real
 * operator's write access belongs.
 */
@Component
public class ReadOnlyActuatorFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method)) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader(HttpHeaders.ALLOW, "GET, HEAD");
        response.sendError(HttpStatus.METHOD_NOT_ALLOWED.value(), "The actuator is read-only");
    }
}
