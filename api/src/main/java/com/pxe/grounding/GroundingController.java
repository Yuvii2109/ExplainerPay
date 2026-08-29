package com.pxe.grounding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxe.deviation.DeviationDetection;
import com.pxe.explain.AiClient;
import com.pxe.explain.ModelCall;
import com.pxe.explain.ModelCallRepository;
import com.pxe.explain.RuleHit;
import com.pxe.explain.RuleHitRepository;
import com.pxe.timeline.Timeline;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The grounding contract, exposed.
 *
 * <p>The probe runs a deliberately malformed response through the real validator — the same code
 * path a live generation takes — and returns the verdict. It exists because a safety rule visibly
 * catching the model is worth more than any number of correct outputs, and a rule nobody can watch
 * fire is indistinguishable from a rule that does not exist.
 *
 * <p>It is a probe and not a backdoor: it cannot write an explanation, and every payload it rejects
 * is written to the rejection log exactly as a real one would be.
 */
@RestController
@RequestMapping("/api/grounding")
public class GroundingController {

    /** Malformed responses, each violating exactly one rule. Beat 10 is {@code G4}. */
    private static final Map<String, List<AiClient.Claim>> VIOLATIONS = Map.of(
            "G4", List.of(new AiClient.Claim("c1", "FACT",
                    "The retry succeeded but the settlement run skipped the payment of 18,400.",
                    List.of("hop:6"), Map.of())),
            "G1", List.of(new AiClient.Claim("c1", "FACT",
                    "The switch acknowledged the transfer.",
                    List.of("hop:99"), Map.of())),
            "G6", List.of(new AiClient.Claim("c1", "HYPOTHESIS",
                    "The settlement selector bound a superseded reference.",
                    List.of("hop:8"), Map.of())),
            "G3", List.of(new AiClient.Claim("c1", "FACT",
                    "The batch closed without the payment worth {amount_1}.",
                    List.of("hop:9"), Map.of("amount_1", "hops[41].amountMinor"))),
            "G9", List.of(new AiClient.Claim("c1", "FACT",
                    "The reference changed between attempts.",
                    List.of("hop:4", "hop:6"), Map.of())));

    private final GroundingValidator validator;
    private final CauseTaxonomy taxonomy;
    private final DeviationDetection detection;
    private final ModelCallRepository modelCalls;
    private final RuleHitRepository ruleHits;
    private final ObjectMapper mapper;

    public GroundingController(GroundingValidator validator, CauseTaxonomy taxonomy,
                               DeviationDetection detection, ModelCallRepository modelCalls,
                               RuleHitRepository ruleHits, ObjectMapper mapper) {
        this.validator = validator;
        this.taxonomy = taxonomy;
        this.detection = detection;
        this.modelCalls = modelCalls;
        this.ruleHits = ruleHits;
        this.mapper = mapper;
    }

    @GetMapping("/rules")
    public List<Rule> rules() {
        return List.of(
                new Rule("G1", "Every citation resolves to a real hop, reference, rule or code",
                        "Drop the claim"),
                new Rule("G2", "Every cited record belongs to this payment", "Drop the claim"),
                new Rule("G3", "Every placeholder resolves to a typed field on a cited record",
                        "Drop the claim"),
                new Rule("G4", "No amount, timestamp or reference appears as a literal in text",
                        "Reject the whole response"),
                new Rule("G5", "A FACT claim must be entailed by its citations, not merely adjacent",
                        "Downgrade to HYPOTHESIS"),
                new Rule("G6", "A HYPOTHESIS must cite a rule id or at least two hops",
                        "Drop the claim"),
                new Rule("G7", "The explanation may not assert a terminal status the record contradicts",
                        "Reject the whole response"),
                new Rule("G8", "If every FACT claim is dropped, do not render L4", "Fall back"),
                new Rule("G9", "The root cause is drawn from the closed taxonomy",
                        "Reject the whole response"));
    }

    /**
     * Runs a deliberately malformed response through the validator. The rejection is logged with
     * its payload, so what was thrown away can be read back rather than described.
     */
    @PostMapping("/probe/{paymentId}/{rule}")
    @Transactional
    public ResponseEntity<Probe> probe(@PathVariable String paymentId, @PathVariable String rule) {
        List<AiClient.Claim> claims = VIOLATIONS.get(rule.toUpperCase());
        if (claims == null) {
            return ResponseEntity.badRequest().build();
        }

        Timeline timeline = detection.timelineOf(paymentId);
        String cause = "G9".equals(rule.toUpperCase())
                ? "A_CAUSE_THIS_SYSTEM_CANNOT_NAME"
                : "RRN_MUTATION_ON_RETRY";

        Grounding verdict = validator.validate(timeline, claims, cause, taxonomy.causes(),
                List.of());

        String payload = json(claims);
        if (verdict.rejected()) {
            modelCalls.save(ModelCall.probeRejection(paymentId, verdict.rejectedBy(),
                    verdict.detail(), payload));
        }
        return ResponseEntity.ok(new Probe(paymentId, rule.toUpperCase(), payload, verdict));
    }

    /** The rejection log. What the contract threw away, and why. */
    @GetMapping("/rejections")
    public List<Rejection> rejections() {
        return modelCalls.findAll().stream()
                .filter(c -> c.getRejectedBy() != null)
                .map(c -> new Rejection(c.getPaymentId(), c.getJob(), c.getRejectedBy(),
                        c.getReason(), c.getRejectedPayload()))
                .toList();
    }

    /** Which rules fired on a payment, for the explanation panel. */
    @GetMapping("/hits/{paymentId}")
    public List<String> hits(@PathVariable String paymentId) {
        return ruleHits.findByPaymentId(paymentId).stream().map(RuleHit::getRuleId).toList();
    }

    private String json(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    public record Rule(String id, String rule, String onFailure) {
    }

    public record Probe(String paymentId, String rule, String payload, Grounding verdict) {
    }

    public record Rejection(String paymentId, String job, String rejectedBy, String detail,
                            String payload) {
    }
}
