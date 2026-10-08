package com.vivekpatel.payments.persistence;

import com.vivekpatel.payments.domain.IdempotencyKey;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link IdempotencyKey}. The lookup by (merchant, key) arrives with replay; today
 * the only operation is the insert, and the UNIQUE constraint does the checking.
 */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {
}
