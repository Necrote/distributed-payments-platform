package com.vivekpatel.payments.api;

import com.vivekpatel.payments.api.dto.CreatePaymentRequest;
import com.vivekpatel.payments.api.dto.PaymentResponse;
import com.vivekpatel.payments.domain.Money;
import com.vivekpatel.payments.domain.Payment;
import com.vivekpatel.payments.service.PaymentApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The public API surface, exactly as specified in the project brief. Endpoints not yet built
 * return {@code 501 Not Implemented}; the guide fills them in one at a time.
 *
 * <p><b>Why the endpoints exist before the logic:</b> the shape of the API is a design decision and
 * belongs in the first commit, where a reviewer can argue with it. Writing the controller last is
 * how you end up with an API that is a mirror of your database schema.
 *
 * <p>Keep the controller thin. It validates input, delegates to the application service, and maps
 * the result to HTTP. All state-machine and idempotency logic lives below this layer - if a
 * business rule ever appears in this file, it cannot be unit-tested without a servlet container and
 * it will not survive mutation testing.
 */
@RestController
@RequestMapping("/payments")
@Tag(name = "Payments", description = "Create, inspect, capture and refund payments")
public class PaymentController {

    // Constructor injection, not @Autowired on a field: it makes the dependency visible in the test.
    private final PaymentApplicationService paymentService;

    public PaymentController(PaymentApplicationService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Create a payment. The {@code Idempotency-Key} header is accepted from day one but is only
     * enforced in Phase 2 - see ADR-004.
     *
     * <p>Target behaviour once implemented:
     *
     * <ul>
     *   <li>{@code 201 Created} with a {@code Location} header for a new payment;</li>
     *   <li>{@code 200 OK} with the original body when the same key and the same payload arrive
     *       again;</li>
     *   <li>{@code 422 Unprocessable Entity} when the same key arrives with a different payload.</li>
     * </ul>
     */
    @PostMapping
    @Operation(summary = "Create a payment (idempotent from Phase 2)")
    @ApiResponse(responseCode = "201", description = "Created; Location header points at the payment")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request; the `errors` property names each offending field",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PaymentResponse> createPayment(
            @Parameter(description = "Client-generated key that makes this call safe to retry")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        // The key is claimed inside the create transaction. TODO Phase 2 (Day 8-9): a repeated key
        // still fails on the UNIQUE constraint instead of replaying or returning 422.
        Payment payment =
                paymentService.create(
                        request.merchantId(),
                        Money.of(request.amount(), request.currency()),
                        request.paymentMethodToken(),
                        request.externalReference(),
                        idempotencyKey);
        URI location =
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{paymentId}")
                        .buildAndExpand(payment.getId())
                        .toUri();
        return ResponseEntity.created(location).body(PaymentResponse.from(payment));
    }

    /** Fetch a payment by id. Phase 5 puts a Redis read-through cache in front of this path. */
    @GetMapping("/{paymentId}")
    @Operation(summary = "Fetch a payment by id")
    @ApiResponse(responseCode = "200", description = "The payment")
    @ApiResponse(
            responseCode = "404",
            description = "No payment with this id",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable UUID paymentId) {
        // Not-found is an exception, not an Optional here: ApiExceptionHandler owns the 404 shape,
        // so every endpoint that loads a payment reports a missing one identically.
        return ResponseEntity.ok(PaymentResponse.from(paymentService.get(paymentId)));
    }

    /**
     * Capture an authorised payment. This is the endpoint the duplicate-capture concurrency test
     * hammers on Day 10, so it must be safe under two simultaneous callers.
     */
    @PostMapping("/{paymentId}/capture")
    @Operation(summary = "Capture an authorised payment")
    @ApiResponse(responseCode = "200", description = "Captured")
    @ApiResponse(
            responseCode = "404",
            description = "No payment with this id",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The payment is not AUTHORIZED",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PaymentResponse> capturePayment(
            @PathVariable UUID paymentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // TODO Phase 3 (Day 13): there is no processor yet, so a payment only reaches AUTHORIZED
        // through the admin-forced authorisation in AdminPaymentController.
        // TODO Phase 2 (Day 10): optimistic locking must make the second concurrent call lose.
        return ResponseEntity.ok(PaymentResponse.from(paymentService.capture(paymentId)));
    }

    /**
     * Refund a captured payment. Partial refunds are out of scope - say so in the README.
     *
     * <p>Returns the payment in {@code REFUND_PENDING}: the refund is accepted, not yet confirmed.
     */
    @PostMapping("/{paymentId}/refund")
    @Operation(summary = "Refund a captured payment (full refunds only)")
    @ApiResponse(responseCode = "200", description = "Refund accepted; the payment is REFUND_PENDING")
    @ApiResponse(
            responseCode = "404",
            description = "No payment with this id",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The payment is not CAPTURED",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PaymentResponse> refundPayment(
            @PathVariable UUID paymentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // TODO Phase 3: the processor call that moves REFUND_PENDING to REFUNDED (or back to
        // CAPTURED) arrives with the simulator.
        return ResponseEntity.ok(PaymentResponse.from(paymentService.refund(paymentId)));
    }

    /**
     * The ledger entries for a payment. Until Phase 4 there is no ledger service; this endpoint
     * exists so the API contract is visible from the first commit.
     */
    @GetMapping("/{paymentId}/ledger")
    @Operation(summary = "Double-entry ledger lines for a payment (Phase 4)")
    public ResponseEntity<Object> getLedger(@PathVariable UUID paymentId) {
        // TODO Phase 4 (Day 30): proxy or query the ledger service.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(problem());
    }

    private static ProblemDetail problem() {
        ProblemDetail detail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.NOT_IMPLEMENTED, "Not implemented yet - see the build guide.");
        detail.setTitle("Not Implemented");
        return detail;
    }
}
