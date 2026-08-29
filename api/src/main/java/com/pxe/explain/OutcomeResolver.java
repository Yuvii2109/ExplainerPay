package com.pxe.explain;

import com.pxe.model.OutcomeTag;
import com.pxe.model.PaymentHop;
import com.pxe.timeline.Timeline;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Section 7, step 1. The tag and the response code, resolved from the hops.
 *
 * <p>Neither is loaded. {@code expected.tag} in the dataset is golden data, so the pipeline has to
 * arrive at the same answer from the record alone or the eval harness is measuring nothing.
 *
 * <p>The response code is the code of the hop that decided the tag, which is why PXE-006 reports
 * U30 rather than the 00 of the retry that followed it: the timeout is what made it a deemed
 * success, and the later success does not erase how it got there.
 */
@Component
public class OutcomeResolver {

    /** The tag, the code that determined it, and when the payment reached it. */
    public record Outcome(OutcomeTag tag, String responseCode, Instant terminalAt) {
    }

    public Outcome resolve(Timeline t) {
        Optional<PaymentHop> expired = t.first("INTENT_EXPIRED");
        if (expired.isPresent()) {
            return outcome(OutcomeTag.EXPIRED, expired.get(), t);
        }

        Optional<PaymentHop> deemed = coded(t, "U30");
        if (deemed.isPresent()) {
            return outcome(OutcomeTag.DEEMED_SUCCESS, deemed.get(), t);
        }

        Optional<PaymentHop> succeeded = success(t);
        if (succeeded.isPresent()) {
            return outcome(OutcomeTag.SUCCESS, lastApproval(t).orElse(succeeded.get()), t);
        }

        Optional<PaymentHop> failed = withStatus(t, "FAILED");
        if (failed.isPresent()) {
            return outcome(OutcomeTag.FAILED, failed.get(), t);
        }

        Optional<PaymentHop> declined = withStatus(t, "DECLINED");
        if (declined.isPresent()) {
            return outcome(OutcomeTag.DECLINED, declined.get(), t);
        }

        return new Outcome(OutcomeTag.PENDING, null, null);
    }

    /** Money reached the payee, or was captured, or was paid out. A debit alone is not success. */
    private Optional<PaymentHop> success(Timeline t) {
        Optional<PaymentHop> credited = t.at("PAYEE_CREDIT").stream()
                .filter(h -> "OK".equals(h.getStatus()))
                .findFirst();
        if (credited.isPresent()) {
            return credited;
        }
        Optional<PaymentHop> captured = t.first("CAPTURED");
        if (captured.isPresent()) {
            return captured;
        }
        return t.at("PAYOUT_CREDITED").stream()
                .filter(h -> h.getOccurredAt() != null)
                .findFirst();
    }

    /** The last hop that succeeded while carrying a code. For every success here that is 00. */
    private Optional<PaymentHop> lastApproval(Timeline t) {
        return t.hops().stream()
                .filter(h -> "OK".equals(h.getStatus()) && h.getCode() != null)
                .max(Comparator.comparingInt(PaymentHop::getSeq));
    }

    private Optional<PaymentHop> coded(Timeline t, String code) {
        return t.hops().stream().filter(h -> code.equals(h.getCode())).findFirst();
    }

    private Optional<PaymentHop> withStatus(Timeline t, String status) {
        return t.hops().stream().filter(h -> status.equals(h.getStatus())).findFirst();
    }

    private Outcome outcome(OutcomeTag tag, PaymentHop decidedBy, Timeline t) {
        return new Outcome(tag, decidedBy.getCode(), lastRecordedMoment(t));
    }

    private Instant lastRecordedMoment(Timeline t) {
        return t.hops().stream()
                .map(PaymentHop::getOccurredAt)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
    }
}
