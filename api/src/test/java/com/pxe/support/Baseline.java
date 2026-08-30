package com.pxe.support;

import com.pxe.explain.ExplanationPipeline;
import com.pxe.explain.ExplanationRepository;
import com.pxe.explain.ModelCallRepository;
import com.pxe.model.PaymentRepository;
import com.pxe.payable.Payables;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resets the database to the state the deterministic funnel produces on its own.
 *
 * <p>The tests share one Postgres, and the model path mutates it. Without a shared starting point a
 * test would pass or fail depending on whether someone had asked a question of the model first,
 * which is the kind of flake that teaches you to stop trusting the suite.
 *
 * <p>Nothing here calls a model. The suite must never depend on a paid external API: an assertion
 * that costs money and can fail for weather reasons is not an assertion.
 */
@Component
public class Baseline {

    private final ExplanationRepository explanations;
    private final ModelCallRepository modelCalls;
    private final ExplanationPipeline pipeline;
    private final PaymentRepository payments;
    private final Payables payables;

    public Baseline(ExplanationRepository explanations, ModelCallRepository modelCalls,
                    ExplanationPipeline pipeline, PaymentRepository payments, Payables payables) {
        this.explanations = explanations;
        this.modelCalls = modelCalls;
        this.pipeline = pipeline;
        this.payments = payments;
        this.payables = payables;
    }

    /**
     * Twelve payments resolved deterministically, three debts open, zero tokens spent.
     *
     * <p>Payments taken in through a scan are removed too. They are real rows with real debts, so
     * leaving them would make the golden-set assertions depend on how many times somebody had
     * demoed since the last run.
     */
    @Transactional
    public void deterministicOnly() {
        // What is owed goes back to what the file says, or a test would pass or fail depending on
        // which bills an earlier test had settled.
        try {
            payables.reset();
        } catch (IOException e) {
            throw new IllegalStateException("payables could not be reset", e);
        }
        payments.findAll().stream()
                .filter(p -> p.getId().startsWith("PAY-"))
                .forEach(payments::delete);
        // The explanations of a deleted payment go with it, through the foreign key. Without this
        // flush the next query hands back rows the database has already removed and the delete
        // below fails on a row that is no longer there, which is a stale-object error that only
        // appears once somebody has demoed against this database.
        payments.flush();
        explanations.findAll().stream()
                .filter(e -> "MODEL".equals(e.getPath()) || "ABSTAIN".equals(e.getPath()))
                .forEach(explanations::delete);
        modelCalls.deleteAllInBatch();
        pipeline.resolveAll();
    }
}
