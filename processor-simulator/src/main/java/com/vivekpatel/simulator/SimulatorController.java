package com.vivekpatel.simulator;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two APIs in one service.
 *
 * <p><b>The fake processor</b> ({@code /authorize}, {@code /capture}, {@code /refund}) is what
 * payment-service calls. <b>The control plane</b> ({@code /control/*}) is what your tests and your
 * demos call to decide how the processor is going to misbehave next.
 *
 * <p>Keeping the control plane in the same service is a conscious trade-off: it is not how you
 * would build a real test double for a shared environment, but it makes a failure scenario a single
 * curl, which means the scenarios actually get run.
 */
@RestController
public class SimulatorController {

    private final SimulatorProperties properties;

    public SimulatorController(SimulatorProperties properties) {
        this.properties = properties;
    }

    /**
     * Authorise a payment.
     *
     * <p>TODO Phase 3 (Day 13): implement the switch over {@link FailureMode} - a Java 21 switch
     * expression over the enum, so adding a mode without handling it fails to compile:
     *
     * <pre>
     * return switch (properties.getMode()) {
     *     case NORMAL   -&gt; sleepThenApprove(properties.getBaseLatency());
     *     case SLOW     -&gt; sleepThenApprove(properties.getSlowLatency());
     *     case TIMEOUT  -&gt; sleepForever();
     *     case HTTP_500 -&gt; ResponseEntity.internalServerError().build();
     *     case HTTP_429 -&gt; rateLimited();
     *     ...
     * };
     * </pre>
     *
     * <p>The response must echo {@code requestId} so the caller can correlate a late response with
     * the attempt that produced it - that correlation is the entire point of the
     * {@link FailureMode#DUPLICATE_RESPONSE} scenario.
     */
    @PostMapping("/authorize")
    public ResponseEntity<Map<String, Object>> authorize(@Valid @RequestBody AuthorizeRequest request) {
        // TODO Phase 3 (Day 13).
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    /** TODO Phase 3 (Day 14): capture an existing authorisation. Same failure-mode switch. */
    @PostMapping("/capture")
    public ResponseEntity<Map<String, Object>> capture(@Valid @RequestBody CaptureRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    /** TODO Phase 3 (Day 14): refund a captured authorisation. Same failure-mode switch. */
    @PostMapping("/refund")
    public ResponseEntity<Map<String, Object>> refund(@Valid @RequestBody RefundRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    // ---------------------------------------------------------------------------------------
    // Control plane
    // ---------------------------------------------------------------------------------------

    /** What is this processor doing right now, and what else could it do? */
    @GetMapping("/control/mode")
    public Map<String, Object> currentMode() {
        return Map.of(
                "mode", properties.getMode().name(),
                "description", properties.getMode().description(),
                "available", java.util.Arrays.stream(FailureMode.values())
                        .collect(java.util.stream.Collectors.toMap(Enum::name, FailureMode::description)));
    }

    /**
     * Switch behaviour at runtime: {@code curl -X PUT 'localhost:8081/control/mode?mode=HTTP_500'}.
     *
     * <p>Spring converts the query parameter to the enum for you and returns 400 for an unknown
     * value, which is the behaviour you want - a typo in a test should fail the test, not silently
     * leave the processor in NORMAL and produce a green run that proved nothing.
     */
    @PutMapping("/control/mode")
    public Map<String, Object> setMode(@RequestParam FailureMode mode) {
        properties.setMode(mode);
        return Map.of("mode", mode.name(), "description", mode.description());
    }

    /** TODO Phase 3 (Day 17): reset counters, clear the in-memory authorisation store, mode=NORMAL. */
    @PostMapping("/control/reset")
    public ResponseEntity<Void> reset() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    /**
     * Requests. Records, because they are immutable data at a service boundary and Java 21 makes
     * that a one-liner. {@code amount} is minor units, as everywhere else in this project.
     */
    public record AuthorizeRequest(
            @NotBlank String requestId,
            @NotBlank String paymentMethodToken,
            @Min(1) long amount,
            @NotBlank String currency) {
    }

    public record CaptureRequest(@NotBlank String requestId, @NotBlank String authorizationId) {
    }

    public record RefundRequest(
            @NotBlank String requestId, @NotBlank String authorizationId, @Min(1) long amount) {
    }
}
