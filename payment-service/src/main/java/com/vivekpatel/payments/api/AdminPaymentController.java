package com.vivekpatel.payments.api;

import com.vivekpatel.payments.api.dto.PaymentResponse;
import com.vivekpatel.payments.service.PaymentApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stand-in for the processor: forces a payment to {@code AUTHORIZED} so that capture and refund can
 * be exercised before Phase 3.
 *
 * <p>An endpoint that authorises without a processor is a way to capture money nobody approved, so
 * the bean does not exist unless {@code payments.admin.force-authorization-enabled=true}. It is off
 * in {@code application.yml} and on only in the {@code test} profile.
 *
 * <p>TODO Phase 3 (Day 13): delete this class and {@code PaymentApplicationService.forceAuthorize}
 * once the processor simulator performs real authorisations.
 */
@RestController
@RequestMapping("/admin/payments")
@ConditionalOnProperty(name = "payments.admin.force-authorization-enabled", havingValue = "true")
@Tag(name = "Admin (temporary)", description = "Test-only shortcuts until the processor exists")
public class AdminPaymentController {

    private final PaymentApplicationService paymentService;

    public AdminPaymentController(PaymentApplicationService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/{paymentId}/force-authorize")
    @Operation(summary = "Force CREATED -> AUTHORIZED without a processor (temporary)")
    public ResponseEntity<PaymentResponse> forceAuthorize(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(PaymentResponse.from(paymentService.forceAuthorize(paymentId)));
    }
}
