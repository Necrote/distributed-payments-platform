package com.vivekpatel.payments.processor;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code payments.processor.*}. Every value is required, and there are deliberately no defaults in
 * this class: an HTTP client's defaults are where infinite timeouts come from (the JDK's
 * {@code HttpClient} has no request timeout unless you set one). A missing value fails startup, not
 * the first slow afternoon in production.
 *
 * @param baseUrl where the processor is, e.g. {@code http://localhost:8081}.
 * @param connectTimeout how long to wait for a TCP connection. Short: if it takes longer than this,
 *     the processor is not there.
 * @param readTimeout how long to wait for the response once the request is sent. Must be below the
 *     client-facing deadline, or our caller gives up before we do and we keep working for nobody.
 */
@ConfigurationProperties(prefix = "payments.processor")
public record ProcessorProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public ProcessorProperties {
        Objects.requireNonNull(baseUrl, "payments.processor.base-url must be set");
        Objects.requireNonNull(connectTimeout, "payments.processor.connect-timeout must be set");
        Objects.requireNonNull(readTimeout, "payments.processor.read-timeout must be set");
    }
}
