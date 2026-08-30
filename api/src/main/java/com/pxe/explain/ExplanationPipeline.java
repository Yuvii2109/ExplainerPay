package com.pxe.explain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxe.admission.AdmissionControl;
import com.pxe.deviation.DeviationDetection;
import com.pxe.deviation.DeviationType;
import com.pxe.grounding.AbstentionRenderer;
import com.pxe.grounding.AudienceRenderer;
import com.pxe.grounding.CauseTaxonomy;
import com.pxe.grounding.Grounding;
import com.pxe.grounding.GroundingValidator;
import com.pxe.model.OutcomeTag;
import com.pxe.model.Payment;
import com.pxe.model.PaymentRepository;
import com.pxe.rules.ResponseCodes;
import com.pxe.rules.RuleCatalogue;
import com.pxe.timeline.Timeline;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Section 7, steps 1 to 10, and the debt.
 *
 * <p>Explanation is a debt and only failure incurs it. A payment tagged SUCCESS with an empty
 * deviation set owes nothing and gets no row here: a clean payment gets a tag, a timeline and a
 * settlement date, and that is all. Everything else opens a debt that stays open until an
 * explanation exists.
 *
 * <p>The funnel runs cheapest first. The response code is consulted before anything else, then the
 * rule catalogue. What neither can explain is left for the model path, which is deliberately
 * <em>not</em> run at startup: the token counter has to be able to sit at zero through a whole demo
 * until a payment has earned the call.
 */
@Component
public class ExplanationPipeline {

    private static final Logger log = LoggerFactory.getLogger(ExplanationPipeline.class);

    private final PaymentRepository payments;
    private final ExplanationRepository explanations;
    private final RuleHitRepository ruleHits;
    private final DeviationDetection detection;
    private final OutcomeResolver outcomes;
    private final RuleCatalogue rules;
    private final ObjectMapper mapper;
    private final AdmissionControl admission;
    private final ModelCallRepository modelCalls;
    private final AiClient ai;
    private final GroundingValidator grounding;
    private final AudienceRenderer renderer;
    private final AbstentionRenderer abstention;
    private final CauseTaxonomy taxonomy;

    public ExplanationPipeline(PaymentRepository payments, ExplanationRepository explanations,
                               RuleHitRepository ruleHits, DeviationDetection detection,
                               OutcomeResolver outcomes, RuleCatalogue rules, ObjectMapper mapper,
                               AdmissionControl admission, ModelCallRepository modelCalls,
                               AiClient ai, GroundingValidator grounding,
                               AudienceRenderer renderer, AbstentionRenderer abstention,
                               CauseTaxonomy taxonomy) {
        this.payments = payments;
        this.explanations = explanations;
        this.ruleHits = ruleHits;
        this.detection = detection;
        this.outcomes = outcomes;
        this.rules = rules;
        this.mapper = mapper;
        this.admission = admission;
        this.modelCalls = modelCalls;
        this.ai = ai;
        this.grounding = grounding;
        this.renderer = renderer;
        this.abstention = abstention;
        this.taxonomy = taxonomy;
    }

    /**
     * Resolves every payment through the deterministic funnel.
     *
     * <p>A payment whose explanation was produced from the same fact set is left alone. Section 16
     * says an explanation whose inputs have not changed is never regenerated, and honouring that
     * here is what stops a restart from re-spending tokens on the model path.
     */
    @Transactional
    public void resolveAll() {
        Instant at = Instant.now();

        int owed = 0;
        int viaCode = 0;
        int viaRule = 0;
        int unexplained = 0;

        for (Payment payment : payments.findAll()) {
            Timeline timeline = detection.timelineOf(payment.getId());
            Set<DeviationType> deviations = detection.detect(timeline);

            OutcomeResolver.Outcome outcome = outcomes.resolve(timeline);
            payment.resolveOutcome(outcome.tag(), outcome.responseCode(), outcome.terminalAt());

            if (!owesAnExplanation(outcome.tag(), deviations)) {
                payment.oweNothing();
                payments.save(payment);
                continue;
            }

            owed++;
            String hash = FactSetHash.of(timeline, deviations);

            Optional<Explanation> cached = explanations.findByPaymentId(payment.getId());
            if (cached.isPresent() && cached.get().getFactSetHash().equals(hash)) {
                payment.openDebt(at);
                if (!"ABSTAIN".equals(cached.get().getPath())) {
                    payment.closeDebt(at);
                }
                payments.save(payment);
                if ("CODE".equals(cached.get().getPath())) {
                    viaCode++;
                } else if ("RULE".equals(cached.get().getPath())) {
                    viaRule++;
                }
                continue;
            }
            if (cached.isPresent()) {
                // The facts moved. The old answer was about a different question.
                explanations.delete(cached.get());
            }
            ruleHits.findByPaymentId(payment.getId()).forEach(ruleHits::delete);

            payment.openDebt(at);

            Optional<String> byCode = ResponseCodes.rootCause(outcome.responseCode());
            if (byCode.isPresent()) {
                explanations.save(determined(payment.getId(), "CODE", hash, byCode.get(),
                        codeCitations(timeline, outcome.responseCode()), timeline, at));
                payment.closeDebt(at);
                viaCode++;
            } else {
                Optional<RuleCatalogue.Match> byRule = rules.firstMatch(timeline);
                if (byRule.isPresent()) {
                    RuleCatalogue.Match match = byRule.get();
                    explanations.save(determined(payment.getId(), "RULE", hash, match.rootCause(),
                            match.citations(), timeline, at));
                    ruleHits.save(new RuleHit(payment.getId(), match.ruleId(), at,
                            json(match.hops())));
                    payment.closeDebt(at);
                    viaRule++;
                } else {
                    unexplained++;
                }
            }
            payments.save(payment);
        }

        log.info("explanation pipeline: {} payments, {} owed an explanation, {} by code, "
                        + "{} by rule, {} left for the model, {} debts still open",
                payments.count(), owed, viaCode, viaRule, unexplained, openDebts());
    }

    /**
     * The model path, on demand.
     *
     * <p>Job B proposes a cause or refuses to name one. Job A then says it clearly. Both calls are
     * recorded with what they cost, and a model that cannot be reached leaves the debt open rather
     * than producing something shaped like an answer.
     */
    @Transactional
    public Optional<Explanation> explainWithModel(String paymentId) {
        Payment payment = payments.findById(paymentId).orElseThrow();
        Timeline timeline = detection.timelineOf(paymentId);
        Set<DeviationType> deviations = detection.detect(timeline);

        AdmissionControl.Decision decision = admission.decide(payment, deviations);
        if (!decision.admitted()) {
            modelCalls.save(ModelCall.refused(paymentId, "HYPOTHESIS", decision.reason()));
            log.info("admission refused for {}: {}", paymentId, decision.reason());
            return explanations.findByPaymentId(paymentId);
        }

        String hash = FactSetHash.of(timeline, deviations);
        Instant at = Instant.now();

        AiClient.HypothesisResponse hypothesis;
        try {
            hypothesis = ai.hypothesis(timeline, hash, deviations, rules.ruleIds());
        } catch (AiClient.Rejected rejection) {
            // A rejected hypothesis produces nothing. There is no cause to fall back to and no
            // partial answer worth keeping, so the debt stays open and the rejection is the record.
            modelCalls.save(ModelCall.rejected(paymentId, "HYPOTHESIS", "SCHEMA_CONTRACT_VIOLATION",
                    0, 0, 0));
            log.warn("hypothesis rejected for {}, debt stays open: {}", paymentId,
                    rejection.detail());
            return Optional.empty();
        }
        modelCalls.save(ModelCall.spent(paymentId, "HYPOTHESIS", decision.priority(),
                decision.reason(), hypothesis.usage().promptTokens(),
                hypothesis.usage().completionTokens(), hypothesis.usage().latencyMs()));

        AiClient.HypothesisResult proposed = hypothesis.result();

        // Job A can be thrown away without losing Job B. A rejected rendering costs the wording,
        // not the attribution: the cause and its citations survive at L3, and the rejection is
        // recorded rather than retried into compliance.
        AiClient.NarrativeResult said = null;
        String narrativeVersion = "rejected";
        try {
            AiClient.NarrativeResponse narrative = ai.narrative(timeline, hash, deviations,
                    rules.ruleIds(), proposed.rootCause(), proposed.claims(),
                    proposed.determinable());
            modelCalls.save(ModelCall.spent(paymentId, "SYNTHESIS", decision.priority(),
                    "rendering an established fact chain", narrative.usage().promptTokens(),
                    narrative.usage().completionTokens(), narrative.usage().latencyMs()));
            said = narrative.result();
            narrativeVersion = narrative.usage().promptVersion();
        } catch (AiClient.Rejected rejection) {
            modelCalls.save(ModelCall.rejected(paymentId, "SYNTHESIS", "SCHEMA_NO_LITERAL_NUMBER",
                    0, 0, 0));
            log.warn("synthesis rejected for {}, keeping the attribution without the wording: {}",
                    paymentId, rejection.detail());
        }

        // Section 15, applied to everything the model wrote before any of it is believed.
        Grounding verdict = grounding.validate(timeline, proposed.claims(), proposed.rootCause(),
                taxonomy.causes(),
                said == null ? List.of()
                        : List.of(said.merchant(), said.support(), said.engineer()));

        if (verdict.rejected()) {
            modelCalls.save(ModelCall.rejectedWithPayload(paymentId, "SYNTHESIS",
                    verdict.rejectedBy(), verdict.detail(),
                    json(new Object[] {proposed, said})));
            log.warn("{} rejected the response for {}: {}", verdict.rejectedBy(), paymentId,
                    verdict.detail());
            return Optional.empty();
        }

        AudienceRenderer.Rendering shown = renderingFor(timeline, proposed, said, verdict);

        Explanation explanation = explanations.save(Explanation.fromModel(
                paymentId,
                hash,
                hypothesis.usage().promptVersion() + "+" + narrativeVersion,
                proposed.rootCause(),
                proposed.determinable(),
                proposed.confidence(),
                json(new StoredClaims(verdict.rejected(), verdict.kept(), verdict.dropped(),
                        verdict.numbersRendered(), verdict.numbersMatchingTheLedger(),
                        proposed.candidatesConsidered())),
                json(citationsOf(proposed)),
                shown == null ? null : shown.merchant(),
                shown == null ? null : shown.support(),
                shown == null ? null : shown.engineer(),
                at));

        // An abstention does not pay the debt. A cause pays it; an honest refusal to name one
        // means nobody has explained this payment yet, the money is still missing, and a human has
        // to go and get the answer. Closing it here would let the console trend to zero by giving
        // up, which is the one way to make the number lie.
        if (proposed.determinable()) {
            payment.closeDebt(at);
        }
        payments.save(payment);

        log.info("model path for {}: determinable={} cause={} confidence={} rendering={}",
                paymentId, proposed.determinable(), proposed.rootCause(), proposed.confidence(),
                said == null ? "rejected" : "kept");
        return Optional.of(explanation);
    }

    /**
     * A payment that succeeded and did not deviate owes nothing. Anything else does, including a
     * payment that succeeded badly: the customer got their money and something is still wrong.
     */
    private boolean owesAnExplanation(OutcomeTag tag, Set<DeviationType> deviations) {
        return tag != OutcomeTag.SUCCESS || !deviations.isEmpty();
    }

    /**
     * A deterministic attribution, said three ways. The renderer fills slots from the record, so a
     * number in the merchant text came out of the ledger rather than out of a sentence.
     */
    private Explanation determined(String paymentId, String path, String hash, String rootCause,
                                   List<String> citations, Timeline timeline, Instant at) {
        Optional<AudienceRenderer.Rendering> said = renderer.render(rootCause, timeline);
        return Explanation.determined(paymentId, path, hash, rootCause, json(citations), at,
                said.map(AudienceRenderer.Rendering::merchant).orElse(null),
                said.map(AudienceRenderer.Rendering::support).orElse(null),
                said.map(AudienceRenderer.Rendering::engineer).orElse(null));
    }

    /**
     * What the reader actually sees, in order of preference.
     *
     * <p>An abstention renders the absence: what we asked for, what came back, what did not, and
     * which causes the evidence refuses to separate. It never depends on Job A, because the one
     * screen that must always say something is the one where the system is admitting it does not
     * know.
     *
     * <p>Otherwise Job A is used when its output survived the contract, and the validated claims
     * are assembled into a rendering when it did not. G8 stops a narrative being written over
     * dropped facts; it was never meant to leave the panel blank.
     */
    private AudienceRenderer.Rendering renderingFor(Timeline timeline,
                                                    AiClient.HypothesisResult proposed,
                                                    AiClient.NarrativeResult said,
                                                    Grounding verdict) {
        if (!proposed.determinable()) {
            return abstention.render(timeline, proposed.candidatesConsidered()).orElse(null);
        }
        if (said != null && verdict.rendersNarrative()) {
            return new AudienceRenderer.Rendering(
                    substitute(timeline, said.merchant(), said),
                    substitute(timeline, said.support(), said),
                    substitute(timeline, said.engineer(), said));
        }
        log.warn("no usable narrative for {}; assembling one from the surviving claims",
                timeline.payment().getId());
        return renderer.fromClaims(proposed.rootCause(), verdict.kept()).orElse(null);
    }

    /**
     * G3 on the renderings. A slot the record cannot fill leaves the sentence without it rather
     * than leaving a brace on screen; the claims are where a failed substitution costs a claim.
     */
    private String substitute(Timeline timeline, String text, AiClient.NarrativeResult said) {
        return grounding.render(timeline, text, said.placeholders()).orElse(text);
    }

    /**
     * What is kept in the claims column: the verdict, plus the causes the model weighed and could
     * not separate. The eval harness reads groundedness and numeric fidelity off the same object.
     */
    private record StoredClaims(boolean rejected, List<Grounding.Claim> kept,
                                List<Grounding.Dropped> dropped, int numbersRendered,
                                int numbersMatchingTheLedger,
                                List<AiClient.Candidate> candidatesConsidered) {
    }

    private List<String> citationsOf(AiClient.HypothesisResult proposed) {
        return proposed.claims().stream()
                .flatMap(c -> c.citations().stream())
                .distinct()
                .sorted()
                .toList();
    }

    private List<String> codeCitations(Timeline timeline, String code) {
        return timeline.hops().stream()
                .filter(h -> code.equals(h.getCode()))
                .map(h -> "hop:" + h.getSeq())
                .findFirst()
                .map(hop -> List.of(hop, "code:" + code))
                .orElseGet(() -> List.of("code:" + code));
    }

    public long openDebts() {
        return payments.findAll().stream().filter(Payment::isDebtOpen).count();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("citations must serialise", e);
        }
    }
}
