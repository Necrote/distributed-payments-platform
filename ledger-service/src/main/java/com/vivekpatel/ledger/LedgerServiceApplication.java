package com.vivekpatel.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Phase 4 (Day 25). Boots, exposes actuator, and does nothing else until the consumer exists.
 *
 * <p>Committing an empty service early is deliberate: it makes the service boundary a decision in
 * the commit history rather than a refactor later, and it keeps the CI matrix honest from the day
 * the module appears.
 */
@SpringBootApplication
public class LedgerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerServiceApplication.class, args);
    }
}
