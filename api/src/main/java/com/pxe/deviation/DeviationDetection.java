package com.pxe.deviation;

import com.pxe.expectation.Expectation;
import com.pxe.expectation.ExpectationModel;
import com.pxe.model.PaymentHop;
import com.pxe.model.PaymentHopRepository;
import com.pxe.model.PaymentReferenceRepository;
import com.pxe.model.PaymentRepository;
import com.pxe.rules.ResponseCodes;
import com.pxe.timeline.Timeline;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * L2. The deviation catalogue of section 9.1, one method per type.
 *
 * <p>Each detector is a pure predicate over the timeline, the reference rows and the expectation
 * model. The catalogue is the only producer of deviation rows: the four conditions in section 9
 * describe the shapes a deviation takes and are not themselves detectors, because a literal
 * reading of them fires on PXE-003 and PXE-009, neither of which deviates.
 */
@Component
public class DeviationDetection {

    private static final Logger log = LoggerFactory.getLogger(DeviationDetection.class);

    private static final Set<String> CUSTOMER_ENGAGEMENT =
            Set.of("QR_SCANNED", "BANK_SESSION", "PAYER_DEBIT", "NETWORK_AUTH");

    private final PaymentRepository payments;
    private final PaymentHopRepository hops;
    private final PaymentReferenceRepository references;
    private final DeviationRepository deviations;
    private final ExpectationModel expectations;

    public DeviationDetection(PaymentRepository payments, PaymentHopRepository hops,
                              PaymentReferenceRepository references, DeviationRepository deviations,
                              ExpectationModel expectations) {
        this.payments = payments;
        this.hops = hops;
        this.references = references;
        this.deviations = deviations;
        this.expectations = expectations;
    }

    /** Recomputes every payment. Detection is a pure function, so it is safe to repeat. */
    @Transactional
    public void detectAll() {
        deviations.deleteAllInBatch();
        Instant detectedAt = Instant.now();
        int deviating = 0;
        for (var payment : payments.findAll()) {
            Set<DeviationType> found = detect(timelineOf(payment.getId()));
            found.forEach(type -> deviations.save(new Deviation(payment.getId(), type, detectedAt)));
            if (!found.isEmpty()) {
                deviating++;
            }
        }
        log.info("deviation detection: {} payments, {} deviating, {} deviations",
                payments.count(), deviating, deviations.count());
    }

    public Timeline timelineOf(String paymentId) {
        return Timeline.of(
                payments.findById(paymentId).orElseThrow(),
                hops.findByPaymentIdOrderBySeqAsc(paymentId),
                references.findByPaymentIdOrderByHopSeqAsc(paymentId));
    }

    public Set<DeviationType> detect(Timeline t) {
        Set<DeviationType> found = EnumSet.noneOf(DeviationType.class);
        if (noCustomerAction(t)) {
            found.add(DeviationType.NO_CUSTOMER_ACTION);
        }
        if (rrnMutated(t)) {
            found.add(DeviationType.RRN_MUTATED);
        }
        if (unsettled(t)) {
            found.add(DeviationType.UNSETTLED);
        }
        if (batchExclusion(t)) {
            found.add(DeviationType.BATCH_EXCLUSION);
        }
        if (settledLate(t)) {
            found.add(DeviationType.SETTLED_LATE);
        }
        if (amountMismatch(t)) {
            found.add(DeviationType.AMOUNT_MISMATCH);
        }
        if (duplicateSuppressed(t)) {
            found.add(DeviationType.DUPLICATE_SUPPRESSED);
        }
        if (autoReversalPending(t)) {
            found.add(DeviationType.AUTO_REVERSAL_PENDING);
        }
        if (unexplainedTerminal(t)) {
            found.add(DeviationType.UNEXPLAINED_TERMINAL);
        }
        if (ledgerAsymmetry(t)) {
            found.add(DeviationType.LEDGER_ASYMMETRY);
        }
        if (unreconciled(t)) {
            found.add(DeviationType.UNRECONCILED);
        }
        if (retryCascade(t)) {
            found.add(DeviationType.RETRY_CASCADE);
        }
        if (absentTerminalEvent(t)) {
            found.add(DeviationType.ABSENT_TERMINAL_EVENT);
        }
        if (slaBreached(t)) {
            found.add(DeviationType.SLA_BREACHED);
        }
        if (lateCallback(t)) {
            found.add(DeviationType.LATE_CALLBACK);
        }
        if (customerSawFailure(t)) {
            found.add(DeviationType.CUSTOMER_SAW_FAILURE);
        }
        return found;
    }

    /** Nobody ever scanned it. The cheapest possible explanation of an absence. */
    private boolean noCustomerAction(Timeline t) {
        return t.has("INTENT_CREATED") && t.has("INTENT_EXPIRED")
                && t.hops().stream().noneMatch(h -> CUSTOMER_ENGAGEMENT.contains(h.getStage()));
    }

    /** Two references of one kind with different values: the earlier one was superseded. */
    private boolean rrnMutated(Timeline t) {
        return t.references().stream().anyMatch(r -> r.getSupersededBy() != null);
    }

    /** Scheduled into a batch and never paid out. */
    private boolean unsettled(Timeline t) {
        return t.has("SETTLEMENT_SCHEDULED") && !t.occurred("PAYOUT_CREDITED");
    }

    /** The batch closed without it. Skipped rather than rejected. */
    private boolean batchExclusion(Timeline t) {
        return t.hops().stream().anyMatch(h -> Boolean.FALSE.equals(h.getIncluded()));
    }

    /** Credited after the cycle deadline. NETBANKING has no settlement row, so it cannot fire. */
    private boolean settledLate(Timeline t) {
        Optional<Expectation> cycle = expectations.settlement(t.payment().getInstrument());
        Optional<Instant> scheduled = t.occurredAtOf("SETTLEMENT_SCHEDULED");
        Optional<Instant> credited = t.occurredAtOf("PAYOUT_CREDITED");
        if (cycle.isEmpty() || scheduled.isEmpty() || credited.isEmpty()) {
            return false;
        }
        ZoneId zone = ZoneId.of(cycle.get().timezone());
        Instant deadline = scheduled.get().atZone(zone)
                .toLocalDate()
                .plusDays(cycle.get().cycleDays())
                .atTime(LocalTime.parse(cycle.get().creditByLocal()))
                .atZone(zone)
                .toInstant();
        return credited.get().isAfter(deadline);
    }

    /**
     * The amounts that should agree do not. Only the authorized, captured and settled amounts are
     * compared: a released authorization residual is a different quantity, not a disagreement.
     */
    private boolean amountMismatch(Timeline t) {
        List<Long> recorded = new ArrayList<>();
        t.authorizedAmount().ifPresent(recorded::add);
        t.amountAt("CAPTURED").ifPresent(recorded::add);
        t.amountAt("PAYOUT_CREDITED").ifPresent(recorded::add);
        return recorded.size() >= 2 && Set.copyOf(recorded).size() > 1;
    }

    /** The idempotency guard fired. A deviation that records the system working. */
    private boolean duplicateSuppressed(Timeline t) {
        return t.hops().stream().anyMatch(h -> h.getDuplicateOf() != null);
    }

    private boolean autoReversalPending(Timeline t) {
        return t.at("AUTO_REVERSAL_INITIATED").stream().anyMatch(h -> "PENDING".equals(h.getStatus()));
    }

    /** A code that explains nothing on its own. This is the one that can justify a model call. */
    private boolean unexplainedTerminal(Timeline t) {
        return t.hops().stream()
                .map(PaymentHop::getCode)
                .filter(Objects::nonNull)
                .anyMatch(ResponseCodes::explainsNothing);
    }

    /**
     * Debited, not credited, and nothing is putting it back. PXE-010 is also a debit without a
     * credit and does not deviate this way, because the network is already reversing it: the
     * finding is an orphaned debit, not an asymmetric one.
     */
    private boolean ledgerAsymmetry(Timeline t) {
        return t.succeededAt("PAYER_DEBIT")
                && !t.succeededAt("PAYEE_CREDIT")
                && !t.has("AUTO_REVERSAL_INITIATED");
    }

    private boolean unreconciled(Timeline t) {
        return t.at("RECON_BREAK_DETECTED").stream().anyMatch(h -> "OPEN".equals(h.getStatus()));
    }

    /**
     * Three or more attempts at one stage, linked as retries. Three auth attempts is three network
     * fees, so the cost is visible in aggregate. Two attempts is a retry, not a cascade, which is
     * why PXE-006 does not fire here.
     */
    private boolean retryCascade(Timeline t) {
        return t.byStage().values().stream()
                .anyMatch(group -> group.size() >= 3
                        && group.stream().anyMatch(h -> h.getRetryOf() != null));
    }

    /** An expected event that never arrived, where the absence is itself the finding. */
    private boolean absentTerminalEvent(Timeline t) {
        return t.hops().stream().anyMatch(h -> "ABSENT".equals(h.getStatus()));
    }

    /**
     * Absence past its SLA. An event that arrived late is LATE_CALLBACK; this is an event that has
     * not arrived at all. The elapsed wait is read from the record, never from a wall clock, which
     * is why PXE-011's missing credit does not fire: nothing recorded how long it has been missing.
     */
    private boolean slaBreached(Timeline t) {
        for (PaymentHop hop : t.notOccurred()) {
            Optional<Long> waited = elapsedMs(hop);
            Optional<Expectation> row = expectations
                    .slaForStageThatDidNotOccur(t.payment().getInstrument(), hop.getStage());
            if (waited.isPresent() && row.isPresent() && waited.get() > row.get().slaMs()) {
                return true;
            }
        }
        return false;
    }

    /** The bank confirmed after we had given up and told the customer it failed. */
    private boolean lateCallback(Timeline t) {
        Optional<Instant> abandoned = t.occurredAtOf("SESSION_ABANDONED");
        return abandoned.isPresent() && t.at("CALLBACK_RECEIVED").stream()
                .anyMatch(h -> h.getOccurredAt() != null && h.getOccurredAt().isAfter(abandoned.get()));
    }

    /**
     * Derived, not observed. It exists to drive the duplicate-payment check: the customer saw a
     * failure we later reconciled forward to success, and may have paid again by another method.
     */
    private boolean customerSawFailure(Timeline t) {
        return t.has("SESSION_ABANDONED") && t.at("STATE_RECONCILED").stream()
                .anyMatch(h -> "SUCCESS".equals(h.getAttrs().get("to")));
    }

    private static Optional<Long> elapsedMs(PaymentHop hop) {
        Object hours = hop.getAttrs().get("elapsedHours");
        return hours instanceof Number n
                ? Optional.of(n.longValue() * 3_600_000L)
                : Optional.empty();
    }
}
