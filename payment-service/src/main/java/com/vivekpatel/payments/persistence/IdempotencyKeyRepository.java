package com.vivekpatel.payments.persistence;

import com.vivekpatel.payments.domain.IdempotencyKey;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link IdempotencyKey}. The insert is the check - the UNIQUE constraint decides
 * who owns a key. The lookup is only for the loser, to fetch what to replay.
 */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    /** Served by the {@code uq_idempotency_key_per_merchant} index; no extra index needed. */
    Optional<IdempotencyKey> findByMerchantIdAndIdempotencyKey(String merchantId, String idempotencyKey);
}
