package com.pxe.explain;

import com.pxe.model.PaymentRepository;
import com.pxe.stream.PaymentView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The explanation of one payment, and the debt queue. */
@RestController
@RequestMapping("/api")
public class ExplanationController {

    private final PaymentRepository payments;
    private final ExplanationRepository explanations;
    private final RuleHitRepository ruleHits;
    private final ExplanationPipeline pipeline;
    private final PaymentView view;

    public ExplanationController(PaymentRepository payments, ExplanationRepository explanations,
                                 RuleHitRepository ruleHits, ExplanationPipeline pipeline,
                                 PaymentView view) {
        this.payments = payments;
        this.explanations = explanations;
        this.ruleHits = ruleHits;
        this.pipeline = pipeline;
        this.view = view;
    }

    @GetMapping("/payments/{id}/explanation")
    public ResponseEntity<Explained> forPayment(@PathVariable String id) {
        return explanations.findByPaymentId(id)
                .map(e -> ResponseEntity.ok(new Explained(
                        e.getPaymentId(),
                        e.getLevel(),
                        e.getPath(),
                        e.getRootCause(),
                        e.getFactSetHash(),
                        e.getCitations(),
                        ruleHits.findByPaymentId(id).stream().map(RuleHit::getRuleId).toList(),
                        e.isHypothesis(),
                        e.isAbstained(),
                        e.getConfidence() == null ? null : e.getConfidence().doubleValue(),
                        e.getPromptVersion())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Ask why. This is the only route in the system that can spend a token, and it is a POST for
     * that reason: it is not a read.
     *
     * <p>Admission control runs first and may refuse. A model that is unreachable returns 503 and
     * the debt stays open, because a payment with no explanation is a better outcome than a payment
     * with an invented one.
     */
    @PostMapping("/payments/{id}/explain")
    public ResponseEntity<PaymentView.Snapshot> explain(@PathVariable String id) {
        if (payments.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            if (pipeline.explainWithModel(id).isEmpty()) {
                // The model answered and the answer was thrown away. No explanation exists, the
                // debt is still open, and saying so is better than returning something shaped
                // like an answer.
                return ResponseEntity.unprocessableEntity().build();
            }
        } catch (AiClient.Unavailable e) {
            return ResponseEntity.status(503).build();
        }
        // The whole payment, in the shape the screen already renders. Answering with a narrower
        // record was how an explanation reached the panel carrying no prose: the screen showed
        // "no rendering at this level" for text the pipeline had in fact just written.
        return view.of(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The queue, sorted by exposure. This is the ops screen and the one that makes the debt idea
     * concrete: a number that should trend to zero.
     */
    @GetMapping("/debt")
    public Debt debt() {
        Instant now = Instant.now();
        List<Owed> open = payments.findByDebtOpenTrueOrderByAmountMinorDesc().stream()
                .map(p -> new Owed(
                        p.getId(),
                        p.getAmountMinor(),
                        p.getCurrency(),
                        p.getTag() == null ? null : p.getTag().name(),
                        p.getResponseCode(),
                        p.getDebtOpenedAt(),
                        p.getDebtOpenedAt() == null ? 0
                                : Duration.between(p.getDebtOpenedAt(), now).toSeconds(),
                        view.header(p).deviations()))
                .toList();
        long exposure = open.stream().mapToLong(Owed::amountMinor).sum();
        return new Debt(open.size(), exposure, open);
    }

    public record Explained(String paymentId, String level, String path, String rootCause,
                            String factSetHash, String citations, List<String> rules,
                            boolean hypothesis, boolean abstained, Double confidence,
                            String promptVersion) {
    }

    public record Owed(String paymentId, long amountMinor, String currency, String tag,
                       String responseCode, Instant openedAt, long ageSeconds,
                       List<String> deviations) {
    }

    public record Debt(int open, long exposureMinor, List<Owed> queue) {
    }
}
