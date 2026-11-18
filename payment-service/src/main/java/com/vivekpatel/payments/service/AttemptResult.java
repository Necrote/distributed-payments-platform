package com.vivekpatel.payments.service;

import com.vivekpatel.payments.domain.AttemptOutcome;

/**
 * How one processor call ended, in the attempt table's terms.
 *
 * @param processorReference the processor's id for the authorisation; {@code null} unless approved.
 * @param errorCode a decline code or a technical failure code; {@code null} on success.
 */
public record AttemptResult(AttemptOutcome outcome, String processorReference, String errorCode) {
}
