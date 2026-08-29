package com.pxe.admission;

import com.pxe.deviation.DeviationType;
import com.pxe.explain.ExplanationRepository;
import com.pxe.model.Payment;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Section 7, step 7. Is this payment worth a model call?
 *
 * <p>Sending millions of payments a day to a model is economically absurd and operationally
 * unauditable. Being able to say precisely which ones were sent, and why, is the interesting part —
 * so a refusal is recorded as carefully as an admission.
 *
 * <p>Everything cheaper has already run by the time this is asked: the response code, then the rule
 * catalogue. What arrives here is only what neither could account for.
 */
@Component
public class AdmissionControl {

    /** Admitted or not, why, and how much money is exposed if the answer stays unknown. */
    public record Decision(boolean admitted, long priority, String reason) {

        public static Decision no(String reason) {
            return new Decision(false, 0, reason);
        }
    }

    private final ExplanationRepository explanations;

    public AdmissionControl(ExplanationRepository explanations) {
        this.explanations = explanations;
    }

    public Decision decide(Payment payment, Set<DeviationType> deviations) {
        if (explanations.findByPaymentId(payment.getId()).isPresent()) {
            return Decision.no("already explained; an explanation whose inputs have not changed "
                    + "is never regenerated");
        }
        if (!payment.isDebtOpen()) {
            return Decision.no("nothing owed; a payment that worked owes no explanation");
        }
        if (deviations.isEmpty()) {
            return Decision.no("no deviation to explain");
        }
        return new Decision(true, payment.getAmountMinor(),
                "no response code and no rule accounted for " + deviations.size()
                        + " deviation(s); exposure " + payment.getAmountMinor());
    }
}
