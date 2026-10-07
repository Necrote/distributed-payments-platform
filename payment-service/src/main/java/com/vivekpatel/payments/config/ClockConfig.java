package com.vivekpatel.payments.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one place the application reads the wall clock. Everything else receives a {@link Clock},
 * so a test can swap in {@code Clock.fixed(...)} and assert exact timestamps.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
