package com.vivekpatel.payments.processor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The one place this service talks HTTP to the payment processor.
 *
 * <p>Its job is translation: whatever happened on the wire comes out as exactly one of
 * <ul>
 *   <li>an {@link AuthorizationDecision} - the processor answered (approved or declined); or</li>
 *   <li>a {@link ProcessorException} - it did not, sorted by what we can still know about the
 *       money.</li>
 * </ul>
 * No Spring or JDK HTTP exception escapes this class, so callers never have to know which client
 * library sits underneath, and the resilience layer (Phase 3) has three exception types to reason
 * about instead of dozens.
 *
 * <p><b>Timeouts are explicit.</b> The request factory is chosen and configured here rather than
 * left to Spring Boot's classpath detection, so the timeouts in {@link ProcessorProperties} are the
 * ones in force and nothing depends on which HTTP library happens to be on the classpath.
 *
 * <p>No retries here, deliberately. Whether a failure may be retried depends on what it was and on
 * the operation; that decision belongs to the caller, not the transport.
 */
@Component
public class ProcessorClient {

    private final RestClient restClient;

    public ProcessorClient(RestClient.Builder builder, ProcessorProperties properties) {
        HttpClient httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(properties.connectTimeout())
                        // HTTP/1.1 explicitly: the JDK client's default tries an h2c upgrade on
                        // plain http, which is one more thing that can behave differently per
                        // server.
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        // The JDK client has no read timeout of its own: without this, a processor that accepts
        // the connection and never answers holds this thread forever.
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }

    /**
     * @return the processor's answer.
     * @throws ProcessorException if there was no answer; the subtype says what is known.
     */
    public AuthorizationDecision authorize(AuthorizeRequest request) {
        try {
            return restClient.post()
                    .uri("/authorize")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((httpRequest, response) -> decide(request, response));
        } catch (ResourceAccessException e) {
            throw classify(e);
        } catch (RestClientException e) {
            // Anything else the client library throws. We cannot show the request did not go out,
            // so it is not safe to call it NotProcessed.
            throw new ProcessorException.OutcomeUnknown("CLIENT_ERROR", e);
        }
    }

    private static AuthorizationDecision decide(
            AuthorizeRequest request, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.is4xxClientError()) {
            // The processor looked at the request and refused it. Nothing was charged.
            throw new ProcessorException.NotProcessed("HTTP_" + status.value(), null);
        }
        if (!status.is2xxSuccessful()) {
            // A 5xx may have failed before the charge or after it. Only the processor knows.
            throw new ProcessorException.OutcomeUnknown("HTTP_" + status.value(), null);
        }
        AuthorizeResponse body;
        try {
            body = response.bodyTo(AuthorizeResponse.class);
        } catch (RestClientException e) {
            throw new ProcessorException.OutcomeUnknown("MALFORMED_RESPONSE", e);
        }
        // An answer to someone else's request is not an answer to ours.
        if (body == null || !request.requestId().equals(body.requestId())) {
            throw new ProcessorException.OutcomeUnknown("MALFORMED_RESPONSE", null);
        }
        if ("APPROVED".equals(body.status()) && body.authorizationId() != null) {
            return new AuthorizationDecision.Approved(body.authorizationId());
        }
        if ("DECLINED".equals(body.status())) {
            return new AuthorizationDecision.Declined(
                    body.declineCode() != null ? body.declineCode() : "UNSPECIFIED");
        }
        throw new ProcessorException.OutcomeUnknown("MALFORMED_RESPONSE", null);
    }

    /**
     * Sorts an I/O failure by one question: could the processor have received the request?
     *
     * <p>Connection refused, or not established within the connect timeout: no request bytes were
     * sent, so nothing was charged. A timeout after that point, or a connection that broke
     * mid-exchange: the request may have been processed.
     *
     * <p>Order matters: {@link HttpConnectTimeoutException} is a subclass of
     * {@link HttpTimeoutException}, so it has to be checked first or every connect failure would
     * be reported as a read timeout - and a definite "not charged" would become a "maybe".
     *
     * <p>Two shapes of read timeout, because it depends on who enforces it. Spring 6.1's
     * {@link JdkClientHttpRequestFactory} waits on the response future itself, so its read timeout
     * arrives as a {@link TimeoutException} inside a plain {@code IOException}; the JDK client's
     * own request timeout arrives as {@link HttpTimeoutException}. Found by
     * {@code ProcessorClientTest}, not by reading documentation: without that test every timeout
     * would have been recorded as UNKNOWN.
     *
     * <p>If the connect timeout were ever set longer than the read timeout, a hung connect would be
     * cut off by the read timeout and reported as TIMEOUT. That errs the safe way - "maybe charged"
     * for something that was not - but keep connect well below read.
     */
    static ProcessorException classify(ResourceAccessException e) {
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException || t instanceof ConnectException) {
                return new ProcessorException.NotProcessed("CONNECT_FAILED", e);
            }
            if (t instanceof HttpTimeoutException || t instanceof TimeoutException) {
                return new ProcessorException.TimedOut(e);
            }
        }
        return new ProcessorException.OutcomeUnknown("IO_ERROR", e);
    }

    /**
     * The processor's response. Fields it sends that we do not use (amount, currency) are ignored
     * here rather than relying on the shared mapper's configuration.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthorizeResponse(
            String requestId, String authorizationId, String status, String declineCode) {
    }
}
