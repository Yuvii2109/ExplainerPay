package com.pxe.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import com.pxe.deviation.DeviationDetection;
import com.pxe.explain.AiClient;
import com.pxe.support.Baseline;
import com.pxe.timeline.Timeline;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 7: G1 to G9 against real payments. Requires the compose stack.
 *
 * <p>The exit criterion is the G4 case — a deliberately malformed response carrying a literal
 * amount must be rejected whole and logged — but a validator that only enforces one rule is a
 * validator nobody should trust, so every rule that can fire is exercised here.
 */
@SpringBootTest
@ActiveProfiles("test")
class GroundingValidatorTest {

    @Autowired
    GroundingValidator validator;
    @Autowired
    CauseTaxonomy taxonomy;
    @Autowired
    AudienceRenderer renderer;
    @Autowired
    DeviationDetection detection;
    @Autowired
    Baseline baseline;

    @BeforeEach
    void deterministicBaseline() {
        baseline.deterministicOnly();
    }

    private Timeline timeline(String id) {
        return detection.timelineOf(id);
    }

    private AiClient.Claim claim(String kind, String text, List<String> citations,
                                 Map<String, String> placeholders) {
        return new AiClient.Claim("c1", kind, text, citations, placeholders);
    }

    private Grounding validate(String payment, List<AiClient.Claim> claims) {
        return validator.validate(timeline(payment), claims, "RRN_MUTATION_ON_RETRY",
                taxonomy.causes(), List.of());
    }

    @Test
    void g4RejectsTheWholeResponseForOneLiteralNumber() {
        Grounding verdict = validate("PXE-006", List.of(
                claim("FACT", "The retry succeeded but the batch skipped the payment of 18,400.",
                        List.of("hop:6"), Map.of())));

        assertThat(verdict.rejected())
                .as("a literal digit is a protocol violation, not a content error")
                .isTrue();
        assertThat(verdict.rejectedBy()).isEqualTo("G4");
        assertThat(verdict.detail()).contains("18,400");
        assertThat(verdict.kept()).isEmpty();
    }

    @Test
    void g4RejectsATimestampAndAReferenceJustAsHard() {
        assertThat(validate("PXE-006", List.of(
                claim("FACT", "The debit timed out at 10:00:14.", List.of("hop:4"), Map.of())))
                .rejectedBy()).isEqualTo("G4");

        assertThat(validate("PXE-006", List.of(
                claim("FACT", "The retry ran under RRN 445100000812.", List.of("hop:6"), Map.of())))
                .rejectedBy()).isEqualTo("G4");
    }

    @Test
    void g1DropsAClaimCitingSomethingThatIsNotThere() {
        Grounding verdict = validate("PXE-006", List.of(
                claim("FACT", "The switch acknowledged it.", List.of("hop:99"), Map.of())));

        assertThat(verdict.rejected()).as("a bad citation costs the claim, not the response").isFalse();
        assertThat(verdict.kept()).isEmpty();
        assertThat(verdict.dropped()).singleElement()
                .satisfies(d -> assertThat(d.rule()).isEqualTo("G1"));
    }

    @Test
    void g6DropsAHypothesisStandingOnASingleHop() {
        Grounding verdict = validate("PXE-006", List.of(
                claim("HYPOTHESIS", "The selector bound a stale reference.",
                        List.of("hop:8"), Map.of())));

        assertThat(verdict.dropped()).singleElement()
                .satisfies(d -> assertThat(d.rule()).isEqualTo("G6"));
    }

    @Test
    void g6KeepsAHypothesisThatCitesARule() {
        Grounding verdict = validate("PXE-006", List.of(
                claim("HYPOTHESIS", "The selector bound a stale reference.",
                        List.of("hop:8", "rule:RECON_RRN_MUTATION"), Map.of())));

        assertThat(verdict.dropped()).isEmpty();
        assertThat(verdict.kept()).singleElement()
                .satisfies(c -> assertThat(c.kind()).isEqualTo("HYPOTHESIS"));
    }

    @Test
    void g3DropsAPlaceholderThatResolvesToNothing() {
        Grounding verdict = validate("PXE-006", List.of(
                claim("FACT", "The batch excluded a payment of {amount_1}.",
                        List.of("hop:9"), Map.of("amount_1", "hops[41].amountMinor"))));

        assertThat(verdict.dropped()).singleElement()
                .satisfies(d -> assertThat(d.rule()).isEqualTo("G3"));
    }

    @Test
    void g3DropsAPlaceholderReadingAHopTheClaimDoesNotCite() {
        Grounding verdict = validate("PXE-008", List.of(
                claim("FACT", "The capture was {captured}.",
                        List.of("hop:1"), Map.of("captured", "hops[2].amountMinor"))));

        assertThat(verdict.dropped()).singleElement()
                .satisfies(d -> assertThat(d.why()).contains("does not cite"));
    }

    @Test
    void aResolvedPlaceholderPutsTheLedgerNumberIntoTheSentence() {
        Grounding verdict = validator.validate(timeline("PXE-008"), List.of(
                        claim("FACT", "The merchant captured {captured}.",
                                List.of("hop:3"), Map.of("captured", "hops[2].amountMinor"))),
                "PARTIAL_CAPTURE_DRIFT", taxonomy.causes(), List.of());

        assertThat(verdict.rejected()).isFalse();
        assertThat(verdict.kept()).singleElement()
                .satisfies(c -> assertThat(c.text())
                        .as("the model never typed it, so it cannot have mistyped it")
                        .isEqualTo("The merchant captured 9,400.00."));
        assertThat(verdict.numbersRendered()).isEqualTo(1);
        assertThat(verdict.numbersMatchingTheLedger()).isEqualTo(1);
    }

    @Test
    void g5DowngradesAFactThatStandsOnlyOnARule() {
        Grounding verdict = validate("PXE-006", List.of(
                claim("FACT", "The reference was superseded.",
                        List.of("rule:RECON_RRN_MUTATION"), Map.of())));

        assertThat(verdict.kept()).singleElement()
                .satisfies(c -> assertThat(c.kind())
                        .as("a restatement of a rule is a proposal about the payment, not a fact of it")
                        .isEqualTo("HYPOTHESIS"));
    }

    @Test
    void g7RejectsAnExplanationClaimingMoneyArrivedWhenItDidNot() {
        Grounding verdict = validator.validate(timeline("PXE-006"), List.of(), null,
                taxonomy.causes(),
                List.of("The payment has been settled to you in full."));

        assertThat(verdict.rejectedBy()).isEqualTo("G7");
        assertThat(verdict.detail()).contains("no payout was credited");
    }

    @Test
    void g9RejectsACauseOutsideTheTaxonomy() {
        Grounding verdict = validator.validate(timeline("PXE-011"), List.of(),
                "A_CAUSE_THIS_SYSTEM_CANNOT_NAME", taxonomy.causes(), List.of());

        assertThat(verdict.rejectedBy()).isEqualTo("G9");
    }

    @Test
    void g8RefusesToRenderWhenEveryFactWasDropped() {
        Grounding verdict = validate("PXE-006", List.of(
                claim("FACT", "The switch acknowledged it.", List.of("hop:99"), Map.of())));

        assertThat(verdict.hasFact()).isFalse();
        assertThat(verdict.rendersNarrative())
                .as("no surviving fact means no narrative to render")
                .isFalse();
    }

    @Test
    void everyDeterministicCauseRendersThreeAudiencesWithoutAModel() {
        List<String> causes = List.of("INSUFFICIENT_FUNDS", "INCORRECT_MPIN",
                "INTENT_EXPIRED_NO_ACTION", "RRN_MUTATION_ON_RETRY", "BANK_CUTOFF_MISSED_WEEKEND",
                "PARTIAL_CAPTURE_DRIFT", "DUPLICATE_CALLBACK", "BENEFICIARY_BANK_UNAVAILABLE",
                "ISSUER_SOFT_DECLINE_RETRY_SUCCESS", "LATE_CALLBACK_AFTER_SESSION_CLOSE");
        assertThat(causes).allSatisfy(c -> assertThat(renderer.canRender(c)).isTrue());
    }
}
