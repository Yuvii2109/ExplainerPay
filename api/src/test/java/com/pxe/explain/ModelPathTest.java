package com.pxe.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import com.pxe.support.Baseline;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

/**
 * The model path, with the model stubbed.
 *
 * <p>Deliberately no live call. What is being tested is the contract around the model — admission,
 * marking, cost accounting, and what survives when a generation is thrown away — none of which
 * should depend on a network or on a provider being in a good mood.
 */
@SpringBootTest
@ActiveProfiles("test")
class ModelPathTest {

    @MockBean
    AiClient ai;

    @Autowired
    ExplanationPipeline pipeline;
    @Autowired
    ExplanationRepository explanations;
    @Autowired
    ModelCallRepository modelCalls;
    @Autowired
    com.pxe.model.PaymentRepository payments;
    @Autowired
    Baseline baseline;

    @BeforeEach
    @AfterEach
    void reset() {
        baseline.deterministicOnly();
    }

    private AiClient.HypothesisResponse proposing(String cause, double confidence) {
        return new AiClient.HypothesisResponse(
                new AiClient.HypothesisResult(true, cause, confidence,
                        List.of(new AiClient.Claim("c1", "HYPOTHESIS", "The switch lost state.",
                                List.of("hop:3", "hop:5"), Map.of())),
                        List.of()),
                new AiClient.Usage(1700, 500, 3000, "stub", "hypothesis@test"));
    }

    private AiClient.HypothesisResponse abstaining() {
        return new AiClient.HypothesisResponse(
                new AiClient.HypothesisResult(false, null, null,
                        List.of(new AiClient.Claim("c1", "FACT", "The credit never arrived.",
                                List.of("hop:3"), Map.of())),
                        List.of(new AiClient.Candidate("BANK_CUTOFF_MISSED", "none"))),
                new AiClient.Usage(1400, 400, 2200, "stub", "hypothesis@test"));
    }

    private AiClient.NarrativeResponse saying() {
        return new AiClient.NarrativeResponse(
                new AiClient.NarrativeResult("Your money is not lost.", "Escalate to recon.",
                        "Debit without credit.", Map.of()),
                new AiClient.Usage(1900, 300, 2500, "stub", "synthesis@test"));
    }

    @Test
    void aProposedCauseIsPersistedAsAMarkedHypothesis() {
        given(ai.hypothesis(any(), anyString(), any(), any()))
                .willReturn(proposing("SWITCH_STATE_DESYNC_AFTER_PARTIAL_ACK", 0.78));
        given(ai.narrative(any(), anyString(), any(), any(), anyString(), any(), anyBoolean()))
                .willReturn(saying());

        pipeline.explainWithModel("PXE-011");

        Explanation explanation = explanations.findByPaymentId("PXE-011").orElseThrow();
        assertThat(explanation.getPath()).isEqualTo("MODEL");
        assertThat(explanation.getLevel()).isEqualTo("L4");
        assertThat(explanation.getRootCause()).isEqualTo("SWITCH_STATE_DESYNC_AFTER_PARTIAL_ACK");
        assertThat(explanation.isHypothesis())
                .as("a proposal presented as a finding is the failure mode the design exists to stop")
                .isTrue();
        assertThat(explanation.isAbstained()).isFalse();
        assertThat(explanation.getConfidence().doubleValue()).isEqualTo(0.78);
        assertThat(explanation.getPromptVersion()).isEqualTo("hypothesis@test+synthesis@test");
        assertThat(payments.findById("PXE-011").orElseThrow().isDebtOpen()).isFalse();
    }

    @Test
    void aRefusalToNameACauseIsPersistedAsAnAbstention() {
        given(ai.hypothesis(any(), anyString(), any(), any())).willReturn(abstaining());
        given(ai.narrative(any(), anyString(), any(), any(), any(), any(), anyBoolean()))
                .willReturn(saying());

        pipeline.explainWithModel("PXE-014");

        Explanation explanation = explanations.findByPaymentId("PXE-014").orElseThrow();
        assertThat(explanation.getPath()).isEqualTo("ABSTAIN");
        assertThat(explanation.isAbstained()).isTrue();
        assertThat(explanation.isDeterminable()).isFalse();
        assertThat(explanation.getRootCause())
                .as("cannot be determined is preferred to a plausible guess")
                .isNull();
        assertThat(explanation.getConfidence()).isNull();
    }

    @Test
    void aRejectedRenderingCostsTheWordingAndNotTheAttribution() {
        given(ai.hypothesis(any(), anyString(), any(), any()))
                .willReturn(proposing("MDR_FEE_APPLIED_TWICE_IN_BATCH", 0.71));
        willThrow(new AiClient.Rejected("a claim may not contain a literal digit"))
                .given(ai).narrative(any(), anyString(), any(), any(), anyString(), any(),
                        anyBoolean());

        pipeline.explainWithModel("PXE-012");

        Explanation explanation = explanations.findByPaymentId("PXE-012").orElseThrow();
        assertThat(explanation.getRootCause()).isEqualTo("MDR_FEE_APPLIED_TWICE_IN_BATCH");
        assertThat(explanation.getMerchantText()).isNull();
        assertThat(explanation.getPromptVersion()).endsWith("+rejected");

        assertThat(modelCalls.findByPaymentId("PXE-012"))
                .filteredOn(c -> "SYNTHESIS".equals(c.getJob()))
                .singleElement()
                .satisfies(call -> assertThat(call.getRejectedBy())
                        .isEqualTo("SCHEMA_NO_LITERAL_NUMBER"));
    }

    @Test
    void aRejectedHypothesisLeavesTheDebtOpenRatherThanCrashing() {
        willThrow(new AiClient.Rejected("a claim carries at least one citation"))
                .given(ai).hypothesis(any(), anyString(), any(), any());

        assertThat(pipeline.explainWithModel("PXE-011"))
                .as("there is no partial cause to fall back to, so nothing is produced")
                .isEmpty();

        assertThat(explanations.findByPaymentId("PXE-011")).isEmpty();
        assertThat(payments.findById("PXE-011").orElseThrow().isDebtOpen()).isTrue();
        assertThat(modelCalls.findByPaymentId("PXE-011"))
                .singleElement()
                .satisfies(call -> assertThat(call.getRejectedBy())
                        .isEqualTo("SCHEMA_CONTRACT_VIOLATION"));
    }

    @Test
    void everyCallIsRecordedWithWhatItCost() {
        given(ai.hypothesis(any(), anyString(), any(), any()))
                .willReturn(proposing("SWITCH_STATE_DESYNC_AFTER_PARTIAL_ACK", 0.78));
        given(ai.narrative(any(), anyString(), any(), any(), anyString(), any(), anyBoolean()))
                .willReturn(saying());

        pipeline.explainWithModel("PXE-011");

        assertThat(modelCalls.findByPaymentId("PXE-011")).hasSize(2);
        assertThat(modelCalls.findAll().stream().mapToInt(ModelCall::tokens).sum())
                .isEqualTo(1700 + 500 + 1900 + 300);
    }

    @Test
    void admissionRefusesAPaymentThatOwesNothing() {
        pipeline.explainWithModel("PXE-001");

        assertThat(explanations.findByPaymentId("PXE-001"))
                .as("a clean success is never worth a model call")
                .isEmpty();
        assertThat(modelCalls.findByPaymentId("PXE-001"))
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.isAdmitted()).isFalse();
                    assertThat(call.tokens()).isZero();
                    assertThat(call.getReason()).contains("nothing owed");
                });
    }

    @Test
    void admissionRefusesAPaymentARuleAlreadyExplained() {
        pipeline.explainWithModel("PXE-006");

        assertThat(modelCalls.findByPaymentId("PXE-006"))
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.isAdmitted()).isFalse();
                    assertThat(call.getReason()).contains("already explained");
                });
        assertThat(explanations.findByPaymentId("PXE-006").orElseThrow().getPath())
                .as("the rule answer stands; asking again does not spend a token")
                .isEqualTo("RULE");
    }

    @Test
    void aRefusedCallSpendsNothing() {
        pipeline.explainWithModel("PXE-002");
        pipeline.explainWithModel("PXE-007");

        assertThat(modelCalls.findAll().stream().mapToInt(ModelCall::tokens).sum()).isZero();
    }
}
