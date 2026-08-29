package com.pxe.eval;

import com.pxe.eval.EvalReport.Metric;
import com.pxe.eval.EvalReport.Row;
import com.pxe.eval.EvalReport.Status;
import com.pxe.explain.Explanation;
import com.pxe.explain.ExplanationRepository;
import com.pxe.explain.ModelCall;
import com.pxe.explain.ModelCallRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxe.model.Payment;
import com.pxe.model.PaymentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The golden-set harness. It exists before anything that explains, so that every later decision is
 * measurable rather than a matter of feel.
 *
 * <p>It compares the explanations in the database against ground truth read from the dataset file.
 * Before phase 4 there are no explanations, so it reports zeros — and reports each denominator
 * beside them, because a zero over an empty denominator means nothing has been measured and a zero
 * over a full one means the system is failing.
 */
@Component
public class EvalHarness {

    private static final BigDecimal CONFIDENT = new BigDecimal("0.5");

    private final GoldenSet golden;
    private final ExplanationRepository explanations;
    private final ModelCallRepository modelCalls;
    private final PaymentRepository payments;
    private final ObjectMapper mapper;

    public EvalHarness(GoldenSet golden, ExplanationRepository explanations,
                       ModelCallRepository modelCalls, PaymentRepository payments,
                       ObjectMapper mapper) {
        this.golden = golden;
        this.explanations = explanations;
        this.modelCalls = modelCalls;
        this.payments = payments;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public EvalReport run() {
        Map<String, Explanation> produced = explanations.findAll().stream()
                .collect(Collectors.toMap(Explanation::getPaymentId, e -> e, (a, b) -> a));
        Map<String, List<ModelCall>> calls = modelCalls.findAll().stream()
                .collect(Collectors.groupingBy(ModelCall::getPaymentId));

        List<Row> rows = golden.entries().stream()
                .map(entry -> row(entry, produced.get(entry.paymentId()),
                        calls.getOrDefault(entry.paymentId(), List.of())))
                .toList();

        Map<String, Payment> resolved = payments.findAll().stream()
                .collect(Collectors.toMap(Payment::getId, p -> p, (a, b) -> a));

        return new EvalReport(Instant.now(), rows.size(), produced.size(),
                metrics(rows, produced, calls, resolved), rows);
    }

    private Row row(GoldenSet.Entry entry, Explanation produced, List<ModelCall> calls) {
        String namedCause = produced == null ? null : produced.getRootCause();
        String actualPath = produced == null ? null : produced.getPath();
        int spent = (int) calls.stream().filter(ModelCall::isAdmitted).count();
        return new Row(
                entry.paymentId(),
                entry.expectedPath(),
                actualPath,
                entry.injectedCause(),
                namedCause,
                produced != null,
                spent,
                entry.expectedPath().equals(actualPath),
                namedCause != null && namedCause.equals(entry.injectedCause()));
    }

    private List<Metric> metrics(List<Row> rows, Map<String, Explanation> produced,
                                 Map<String, List<ModelCall>> calls,
                                 Map<String, Payment> resolved) {
        return List.of(
                groundedness(produced),
                numericFidelity(produced),
                causeAccuracy(rows),
                falseAttribution(rows, produced),
                abstentionCorrectness(produced),
                determinism(produced),
                deterministicCoverage(rows, produced, calls, resolved),
                costPerExplainedPayment(produced, calls));
    }

    /**
     * G1 to G3, counted. Measured over model-produced claims only: the deterministic paths emit no
     * claims, and their citations are generated from the record rather than asserted about it.
     */
    private Metric groundedness(Map<String, Explanation> produced) {
        long kept = 0;
        long dropped = 0;
        for (JsonNode verdict : verdicts(produced)) {
            kept += verdict.path("kept").size();
            dropped += verdict.path("dropped").size();
        }
        long total = kept + dropped;
        return Metric.ratio("Groundedness",
                "Claims whose citations and placeholders all resolve, over all claims the model made.",
                kept, total, "100%", "Enforced, G1 to G3",
                total == 0 ? Status.NOT_MEASURED
                        : (kept == total ? Status.MET : Status.NOT_MET));
    }

    /**
     * G4, counted. Every number that reached a reader was substituted from a typed field, because
     * the model is not permitted to type one. A mismatch here would mean substitution itself is
     * broken, which is why the target is total rather than high.
     */
    private Metric numericFidelity(Map<String, Explanation> produced) {
        long rendered = 0;
        long matching = 0;
        for (JsonNode verdict : verdicts(produced)) {
            rendered += verdict.path("numbersRendered").asLong();
            matching += verdict.path("numbersMatchingTheLedger").asLong();
        }
        return Metric.ratio("Numeric fidelity",
                "Numbers substituted from a typed field, over numbers rendered to a reader.",
                matching, rendered, "100%", "Enforced, G4",
                rendered == 0 ? Status.NOT_MEASURED
                        : (matching == rendered ? Status.MET : Status.NOT_MET));
    }

    /** The stored grounding verdicts. Only the model path has one. */
    private List<JsonNode> verdicts(Map<String, Explanation> produced) {
        List<JsonNode> found = new java.util.ArrayList<>();
        for (Explanation explanation : produced.values()) {
            if (explanation.getClaims() == null) {
                continue;
            }
            try {
                JsonNode node = mapper.readTree(explanation.getClaims());
                if (node.has("kept")) {
                    found.add(node);
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // A verdict that will not parse is not a verdict. It counts as nothing rather
                // than as a pass.
            }
        }
        return found;
    }

    /** Did the explanation name the cause the simulator injected? */
    private Metric causeAccuracy(List<Row> rows) {
        List<GoldenSet.Entry> scored = golden.entries().stream()
                .filter(GoldenSet.Entry::mustNameACause)
                .toList();
        long correct = rows.stream()
                .filter(r -> scored.stream().anyMatch(e -> e.paymentId().equals(r.paymentId())))
                .filter(Row::causeCorrect)
                .count();
        return Metric.ratio("Cause accuracy",
                "Explanations naming the injected root cause, over scenarios that owe a named cause.",
                correct, scored.size(), "> 85%", "Measured",
                atLeast(correct, scored.size(), golden.causeAccuracyTarget()));
    }

    /**
     * The metric that matters most and the one nobody reports. A confident wrong cause is worse in
     * operations than an abstention, because it gets repeated to a customer and acted upon. Naming
     * any cause at all on a scenario that must abstain counts here.
     */
    private Metric falseAttribution(List<Row> rows, Map<String, Explanation> produced) {
        long named = rows.stream().filter(r -> r.namedCause() != null).count();
        long wrong = rows.stream()
                .filter(r -> r.namedCause() != null)
                .filter(r -> !r.causeCorrect())
                .filter(r -> confidently(produced.get(r.paymentId())))
                .count();
        return Metric.ratio("False attribution",
                "Explanations naming a wrong cause confidently, over explanations that named one.",
                wrong, named, "< 2%", "Measured",
                atMost(wrong, named, golden.falseAttributionTarget()));
    }

    /** A claim is confident unless it is marked as a hypothesis below the confidence floor. */
    private boolean confidently(Explanation explanation) {
        if (explanation == null) {
            return false;
        }
        if (!explanation.isHypothesis()) {
            return true;
        }
        return explanation.getConfidence() != null
                && explanation.getConfidence().compareTo(CONFIDENT) >= 0;
    }

    /** "Cannot be determined" is always available and is preferred to a plausible guess. */
    private Metric abstentionCorrectness(Map<String, Explanation> produced) {
        List<GoldenSet.Entry> mustAbstain = golden.entries().stream()
                .filter(GoldenSet.Entry::mustAbstain)
                .toList();
        long abstained = mustAbstain.stream()
                .map(e -> produced.get(e.paymentId()))
                .filter(e -> e != null && e.isAbstained())
                .count();
        return Metric.ratio("Abstention correctness",
                "Abstentions on genuinely undeterminable scenarios, over scenarios that require one.",
                abstained, mustAbstain.size(), "> 90%", "Measured",
                atLeast(abstained, mustAbstain.size(), golden.abstentionCorrectnessTarget()));
    }

    /** One fact set, one explanation. The unique index on the cache key is what enforces it. */
    private Metric determinism(Map<String, Explanation> produced) {
        long distinct = produced.values().stream()
                .map(e -> e.getPaymentId() + ":" + e.getFactSetHash())
                .distinct()
                .count();
        return Metric.ratio("Determinism",
                "Explanations with one stable fact-set hash, over all explanations.",
                distinct, produced.size(), "100%", "Enforced by cache",
                produced.isEmpty() ? Status.NOT_MEASURED
                        : (distinct == produced.size() ? Status.MET : Status.NOT_MET));
    }

    /**
     * The funnel claim, measured. The denominator is every payment, not every explained payment:
     * a clean success is deterministically resolved because it needed no model and owes nothing.
     */
    private Metric deterministicCoverage(List<Row> rows, Map<String, Explanation> produced,
                                         Map<String, List<ModelCall>> calls,
                                         Map<String, Payment> resolved) {
        long free = rows.stream()
                .filter(r -> isResolved(r.paymentId(), produced, resolved))
                .filter(r -> calls.getOrDefault(r.paymentId(), List.of()).stream()
                        .noneMatch(ModelCall::isAdmitted))
                .count();
        return Metric.ratio("Deterministic coverage",
                "Payments resolved with no model call, over all payments.",
                free, rows.size(), ">= 80%", "Measured",
                atLeast(free, rows.size(), golden.deterministicCoverageTarget()));
    }

    private Metric costPerExplainedPayment(Map<String, Explanation> produced,
                                           Map<String, List<ModelCall>> calls) {
        long tokens = calls.values().stream()
                .flatMap(List::stream)
                .mapToLong(ModelCall::tokens)
                .sum();
        int explained = produced.size();
        double value = explained == 0 ? 0.0 : (double) tokens / explained;
        return new Metric("Cost per explained payment",
                "Tokens spent, over payments explained.",
                tokens, explained, value, "tokens", "Falling", "Measured",
                explained == 0 ? Status.NOT_MEASURED : Status.MET);
    }

    /**
     * A payment is resolved when it has an explanation, or when it reached a terminal outcome that
     * never owed one. A clean success is deterministically covered precisely because it produced no
     * explanation and cost nothing to not explain.
     */
    private boolean isResolved(String paymentId, Map<String, Explanation> produced,
                               Map<String, Payment> resolved) {
        if (produced.containsKey(paymentId)) {
            return true;
        }
        Payment payment = resolved.get(paymentId);
        return payment != null && payment.getTag() != null && payment.getDebtOpenedAt() == null;
    }

    private static Status atLeast(long numerator, long denominator, double target) {
        if (denominator == 0) {
            return Status.NOT_MEASURED;
        }
        return (double) numerator / denominator >= target ? Status.MET : Status.NOT_MET;
    }

    private static Status atMost(long numerator, long denominator, double target) {
        if (denominator == 0) {
            return Status.NOT_MEASURED;
        }
        return (double) numerator / denominator <= target ? Status.MET : Status.NOT_MET;
    }

    /** Present for the controller: the funnel has regressed if any path differs from its expectation. */
    public Optional<String> funnelRegression(EvalReport report) {
        return report.rows().stream()
                .filter(Row::explained)
                .filter(r -> !r.pathAsExpected())
                .map(r -> "%s expected %s, produced %s"
                        .formatted(r.paymentId(), r.expectedPath(), r.actualPath()))
                .findFirst();
    }
}
