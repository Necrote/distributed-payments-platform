package com.vivekpatel.payments.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vivekpatel.payments.domain.IllegalStateTransitionException;
import com.vivekpatel.payments.domain.Money;
import com.vivekpatel.payments.domain.Payment;
import com.vivekpatel.payments.domain.PaymentStatus;
import com.vivekpatel.payments.api.dto.PaymentResponse;
import com.vivekpatel.payments.service.CreatePaymentResult;
import com.vivekpatel.payments.service.IdempotencyKeyReusedException;
import com.vivekpatel.payments.service.IdempotentPaymentCreator;
import com.vivekpatel.payments.service.PaymentApplicationService;
import com.vivekpatel.payments.service.PaymentNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract, with the service mocked: status codes, the ProblemDetail shape, and what must
 * never appear in a response. No database, so it runs in {@code mvn test} without Docker;
 * {@link PaymentControllerIT} covers the same endpoints against real PostgreSQL.
 */
@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-29T10:15:30Z"), ZoneOffset.UTC);

    private static final String VALID_BODY =
            """
            {
              "merchantId": "merchant-123",
              "amount": 12500,
              "currency": "INR",
              "paymentMethodToken": "tok_test_123",
              "externalReference": "order-827361"
            }
            """;

    @Autowired
    MockMvc mockMvc;

    @MockBean
    PaymentApplicationService paymentService;

    @MockBean
    IdempotentPaymentCreator paymentCreator;

    @Test
    void getKnownPaymentReturnsTheDtoWithoutEntityInternals() throws Exception {
        Payment payment =
                Payment.create("merchant-123", Money.of(12500, "INR"), "tok_test_123", "order-1", FIXED);
        when(paymentService.get(payment.getId())).thenReturn(payment);

        mockMvc.perform(get("/payments/{id}", payment.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(payment.getId().toString()))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.amount").value(12500))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.paymentMethodToken").doesNotExist());
    }

    @Test
    void unknownPaymentIdIs404ProblemDetailNot500() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(paymentService.get(unknown)).thenThrow(new PaymentNotFoundException(unknown));

        mockMvc.perform(get("/payments/{id}", unknown))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Payment not found"))
                .andExpect(jsonPath("$.detail").value(containsString(unknown.toString())))
                .andExpect(jsonPath("$.instance").value("/payments/" + unknown));
    }

    @Test
    void malformedPaymentIdIs400NotAServerError() throws Exception {
        mockMvc.perform(get("/payments/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(paymentService, paymentCreator);
    }

    @Test
    void blankRequiredFieldIsNamedInTheProblemDetail() throws Exception {
        String body = VALID_BODY.replace("\"merchant-123\"", "\"\"");

        mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Request validation failed"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("merchantId"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be blank"));
        verifyNoInteractions(paymentService, paymentCreator);
    }

    @Test
    void everyInvalidFieldIsReportedInAStableOrder() throws Exception {
        String body =
                """
                { "merchantId": "m", "amount": 0, "currency": "INR", "paymentMethodToken": "" }
                """;

        mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(jsonPath("$.errors[1].field").value("paymentMethodToken"));
    }

    @Test
    void wellFormedButUnknownCurrencyIs400NamingCurrency() throws Exception {
        // "XYZ" matches [A-Z]{3}. Without the ISO check it reaches Money and becomes a 500.
        String body = VALID_BODY.replace("\"INR\"", "\"XYZ\"");

        mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("currency"))
                .andExpect(jsonPath("$.errors[0].message").value("must be a 3-letter ISO-4217 code"));
        verifyNoInteractions(paymentService, paymentCreator);
    }

    @Test
    void wrongJsonTypeNamesTheFieldWithoutEchoingJacksonInternals() throws Exception {
        String body = VALID_BODY.replace("12500", "\"ten\"");

        mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(content().string(not(containsString("com.fasterxml"))))
                .andExpect(content().string(not(containsString("\"ten\""))));
    }

    @Test
    void malformedJsonIs400ProblemDetail() throws Exception {
        mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Request body is not readable"));
    }

    @Test
    void validCreateIs201WithLocation() throws Exception {
        Payment payment =
                Payment.create("merchant-123", Money.of(12500, "INR"), "tok_test_123", "order-1", FIXED);
        when(paymentCreator.create(any(), any(), any(), any(), any()))
                .thenReturn(new CreatePaymentResult.Created(payment));

        mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/payments/" + payment.getId()))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.version").doesNotExist());
        verify(paymentCreator)
                .create(eq("merchant-123"), eq(Money.of(12500, "INR")), eq("tok_test_123"),
                        eq("order-827361"), isNull());
    }

    @Test
    void idempotencyKeyHeaderIsPassedToTheService() throws Exception {
        Payment payment =
                Payment.create("merchant-123", Money.of(12500, "INR"), "tok_test_123", "order-1", FIXED);
        when(paymentCreator.create(any(), any(), any(), any(), any()))
                .thenReturn(new CreatePaymentResult.Created(payment));

        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "key-7f3a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated());
        verify(paymentCreator).create(any(), any(), any(), any(), eq("key-7f3a"));
    }

    @Test
    void reusedKeyWithADifferentBodyIs422ThatTellsTheClientWhatToFix() throws Exception {
        UUID original = UUID.randomUUID();
        when(paymentCreator.create(any(), any(), any(), any(), any()))
                .thenThrow(new IdempotencyKeyReusedException("key-7f3a", original, List.of("amount")));

        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "key-7f3a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.title").value("Idempotency-Key reused with a different request"))
                .andExpect(jsonPath("$.detail").value(
                        "Idempotency-Key 'key-7f3a' was already used for payment " + original
                                + " with a different request body. Differing fields: amount. To retry "
                                + "that payment, resend its original body unchanged. To create a new "
                                + "payment, use a new Idempotency-Key."))
                .andExpect(jsonPath("$.idempotencyKey").value("key-7f3a"))
                .andExpect(jsonPath("$.originalPaymentId").value(original.toString()))
                .andExpect(jsonPath("$.differingFields", contains("amount")))
                .andExpect(jsonPath("$.instance").value("/payments"));
    }

    @Test
    void replayIs200WithTheOriginalBodyAndNoLocation() throws Exception {
        Payment payment =
                Payment.create("merchant-123", Money.of(12500, "INR"), "tok_test_123", "order-1", FIXED);
        when(paymentCreator.create(any(), any(), any(), any(), any()))
                .thenReturn(new CreatePaymentResult.Replayed(PaymentResponse.from(payment)));

        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "key-7f3a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                // 200, not 201: the first call created the resource, this one created nothing.
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.id").value(payment.getId().toString()))
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void captureOfACreatedPaymentIs409ProblemDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentService.capture(id))
                .thenThrow(new IllegalStateTransitionException(PaymentStatus.CREATED, PaymentStatus.CAPTURED));

        mockMvc.perform(post("/payments/{id}/capture", id))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Illegal payment state transition"))
                .andExpect(jsonPath("$.detail").value("Payment is CREATED and cannot move to CAPTURED"))
                .andExpect(jsonPath("$.currentStatus").value("CREATED"))
                .andExpect(jsonPath("$.instance").value("/payments/" + id + "/capture"));
    }

    @Test
    void captureOfAnUnknownPaymentIs404() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(paymentService.capture(unknown)).thenThrow(new PaymentNotFoundException(unknown));

        mockMvc.perform(post("/payments/{id}/capture", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Payment not found"));
    }

    @Test
    void captureThatLosesAVersionRaceIs409NotA500() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentService.capture(id))
                .thenThrow(new ObjectOptimisticLockingFailureException(Payment.class, id));

        mockMvc.perform(post("/payments/{id}/capture", id))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Concurrent update"))
                .andExpect(jsonPath("$.detail").value(containsString("Fetch the payment")))
                // Hibernate's message names the entity class; none of it reaches the client.
                .andExpect(content().string(not(containsString("com.vivekpatel"))));
    }

    @Test
    void successfulCaptureAndRefundReturnTheUpdatedPayment() throws Exception {
        Payment payment =
                Payment.create("merchant-123", Money.of(12500, "INR"), "tok_test_123", "order-1", FIXED);
        payment.transitionTo(PaymentStatus.PROCESSING, FIXED);
        payment.transitionTo(PaymentStatus.AUTHORIZED, FIXED);
        payment.transitionTo(PaymentStatus.CAPTURED, FIXED);
        when(paymentService.capture(payment.getId())).thenReturn(payment);

        mockMvc.perform(post("/payments/{id}/capture", payment.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.version").doesNotExist());

        payment.transitionTo(PaymentStatus.REFUND_PENDING, FIXED);
        when(paymentService.refund(payment.getId())).thenReturn(payment);

        mockMvc.perform(post("/payments/{id}/refund", payment.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUND_PENDING"));
    }

    @Test
    void forceAuthorizeEndpointDoesNotExistUnlessEnabled() throws Exception {
        // AdminPaymentController is not part of this slice, and is off by default everywhere else.
        mockMvc.perform(post("/admin/payments/{id}/force-authorize", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        verifyNoInteractions(paymentService, paymentCreator);
    }

    @Test
    void unexpectedFailureIsGeneric500WithNoStackTraceOrMessage() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentService.get(id))
                .thenThrow(new IllegalStateException("connection to db-primary.internal:5432 refused"));

        mockMvc.perform(get("/payments/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(content().string(not(containsString("db-primary"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }
}
