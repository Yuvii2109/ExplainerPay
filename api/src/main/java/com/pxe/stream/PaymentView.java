package com.pxe.stream;

import com.pxe.deviation.DeviationRepository;
import com.pxe.expectation.RailSequences;
import com.pxe.explain.Explanation;
import com.pxe.explain.ExplanationRepository;
import com.pxe.explain.ModelCall;
import com.pxe.explain.ModelCallRepository;
import com.pxe.explain.RuleHit;
import com.pxe.explain.RuleHitRepository;
import com.pxe.model.Merchants;
import com.pxe.model.Payment;
import com.pxe.model.PaymentHop;
import com.pxe.model.PaymentHopRepository;
import com.pxe.model.PaymentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything one payment screen needs, assembled once.
 *
 * <p>The explanation is included whenever it exists, which for the NONE, CODE and RULE paths is
 * before anyone asks. That is the point of section 17.4: those paths are a pure function of the
 * events, so the answer is already on the wire by the time the user clicks "why", and reveal
 * latency for four out of five payments is zero rather than fast.
 */
@Component
public class PaymentView {

    private final PaymentRepository payments;
    private final PaymentHopRepository hops;
    private final DeviationRepository deviations;
    private final ExplanationRepository explanations;
    private final RuleHitRepository ruleHits;
    private final ModelCallRepository modelCalls;
    private final RailSequences rails;
    private final Merchants merchants;

    public PaymentView(PaymentRepository payments, PaymentHopRepository hops,
                       DeviationRepository deviations, ExplanationRepository explanations,
                       RuleHitRepository ruleHits, ModelCallRepository modelCalls,
                       RailSequences rails, Merchants merchants) {
        this.payments = payments;
        this.hops = hops;
        this.deviations = deviations;
        this.explanations = explanations;
        this.ruleHits = ruleHits;
        this.modelCalls = modelCalls;
        this.rails = rails;
        this.merchants = merchants;
    }

    @Transactional(readOnly = true)
    public Optional<Snapshot> of(String paymentId) {
        return payments.findById(paymentId).map(payment -> new Snapshot(
                header(payment),
                rails.skeletonFor(payment.getRail()),
                hops.findByPaymentIdOrderBySeqAsc(paymentId).stream().map(PaymentView::hop).toList(),
                deviations.findByPaymentIdOrderByTypeAsc(paymentId).stream()
                        .map(d -> d.getType().name()).toList(),
                explanations.findByPaymentId(paymentId)
                        .map(e -> explanation(e, ruleHits.findByPaymentId(paymentId)))
                        .orElse(null),
                modelCalls.findByPaymentId(paymentId).stream().mapToInt(ModelCall::tokens).sum()));
    }

    /**
     * The two axes together. A list row that shows only the tag makes a SUCCESS carrying a debt
     * look like a bug, when it is the most interesting row on the screen: the rails are content
     * and the expectation model is not.
     */
    public Header header(Payment payment) {
        return new Header(
                payment.getId(),
                payment.getMerchantId(),
                merchants.name(payment.getMerchantId()),
                payment.getAmountMinor(),
                payment.getCurrency(),
                payment.getInstrument().name(),
                payment.getRail(),
                payment.getTag() == null ? null : payment.getTag().name(),
                payment.getResponseCode(),
                payment.isDebtOpen(),
                payment.getDebtOpenedAt(),
                payment.getDebtClosedAt(),
                deviations.findByPaymentIdOrderByTypeAsc(payment.getId()).stream()
                        .map(d -> d.getType().name())
                        .toList());
    }

    public static Hop hop(PaymentHop h) {
        return new Hop(
                h.getSeq(), h.getStage(), h.getActor(), h.getStatus(), h.getCode(),
                h.getLatencyMs(), h.getOccurredAt(), h.getOccurredAt() == null,
                h.getAmountMinor(), h.getBatch(), h.getNote(), h.getAttrs());
    }

    private static Explained explanation(Explanation e, List<RuleHit> hits) {
        return new Explained(
                e.getLevel(), e.getPath(), e.getRootCause(), e.isDeterminable(),
                e.getConfidence() == null ? null : e.getConfidence().doubleValue(),
                e.isHypothesis(), e.isAbstained(), e.getCitations(), e.getFactSetHash(),
                hits.stream().map(RuleHit::getRuleId).toList(),
                e.getMerchantText(), e.getSupportText(), e.getEngineerText(),
                e.getPromptVersion());
    }

    public record Header(String paymentId, String merchantId, String merchantName,
                         long amountMinor, String currency,
                         String instrument, String rail, String tag, String responseCode,
                         boolean debtOpen, Instant debtOpenedAt, Instant debtClosedAt,
                         List<String> deviations) {
    }

    /** {@code absent} is a hop that did not happen. The timeline draws it rather than skipping it. */
    public record Hop(int seq, String stage, String actor, String status, String code,
                      Long latencyMs, Instant occurredAt, boolean absent, Long amountMinor,
                      String batch, String note, java.util.Map<String, Object> attrs) {
    }

    public record Explained(String level, String path, String rootCause, boolean determinable,
                            Double confidence, boolean hypothesis, boolean abstained,
                            String citations, String factSetHash, List<String> rules,
                            String merchantText, String supportText, String engineerText,
                            String promptVersion) {
    }

    public record Snapshot(Header header, List<String> skeleton, List<Hop> hops,
                           List<String> deviations, Explained explanation, int tokensSpent) {
    }
}
