package com.pxe.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pxe.deviation.DeviationDetection;
import com.pxe.explain.ExplanationPipeline;
import com.pxe.model.PaymentRepository;
import com.pxe.payable.Payables;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A payment goes in.
 *
 * <p>Taking a scan creates a new payment rather than opening an old one: it gets its own id, its
 * own row, its own debt, and it runs the whole pipeline from ingestion to explanation. That is the
 * difference between a demo that replays a record and one that processes something, and it is why
 * the debt counter moves while you watch.
 *
 * <p>The event set is copied from a scenario, because a scenario is how a known failure is
 * injected. The simulator decides what the rails return; saying so out loud is the credible
 * version, since no bank times out on cue.
 *
 * <p>Timestamps are copied unchanged. Rebasing them onto now would look more alive and would
 * quietly break the expectation model: a settlement deadline is an absolute time of day, so the
 * same event set would or would not be late depending on what o'clock it was when you scanned.
 * A demo that reports a different deviation set each time it runs is not a demo of this system.
 */
@Component
public class PaymentIntake {

    private static final Logger log = LoggerFactory.getLogger(PaymentIntake.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ScenarioLoader loader;
    private final PaymentRepository payments;
    private final DeviationDetection detection;
    private final ExplanationPipeline pipeline;
    private final com.pxe.model.Merchants merchants;
    private final Payables payables;

    public PaymentIntake(ScenarioLoader loader, PaymentRepository payments,
                         DeviationDetection detection, ExplanationPipeline pipeline,
                         com.pxe.model.Merchants merchants, Payables payables) {
        this.loader = loader;
        this.payments = payments;
        this.detection = detection;
        this.pipeline = pipeline;
        this.merchants = merchants;
        this.payables = payables;
    }

    /**
     * The id of the payment that was just taken in, and what it did to what was owed.
     *
     * <p>{@code creditedMinor} is what reached the merchant, which is not always what was paid. It
     * is reported separately from the amount so the screen that follows can say which of the two it
     * means.
     */
    public record Taken(String paymentId, String from, long amountMinor, String merchantId,
                        String payableId, long creditedMinor) {
    }

    @Transactional
    public Taken take(String scenarioId, Long amountMinor, String merchantId, String payableId)
            throws IOException {
        JsonNode template = loader.scenario(scenarioId)
                .orElseThrow(() -> new IllegalArgumentException("no scenario " + scenarioId));

        long amount = amountMinor == null ? template.get("amountMinor").asLong() : amountMinor;
        JsonNode scenario = amount == template.get("amountMinor").asLong()
                ? template
                : scaledTo(template, amount);

        String paymentId = "PAY-" + reference();
        String payee = merchantId == null ? merchants.forScenario(scenarioId) : merchantId;
        loader.ingest(scenario, paymentId, payee);
        payments.flush();

        // The new payment has to go through the same funnel as everything else, and the cheapest
        // way to be certain of that is to run the funnel rather than a special case of it.
        detection.detectAll();
        pipeline.resolveAll();

        // Only now, with the timeline resolved, is it knowable what the merchant was credited.
        // Applying it earlier would settle a payable on the strength of an intention.
        long credited = payableId == null ? 0 : payables.apply(payableId, paymentId);

        log.info("took in {} from scenario {} for {} minor to {}", paymentId, scenarioId, amount,
                payee);
        return new Taken(paymentId, scenarioId, amount, payee, payableId, credited);
    }

    /**
     * The same event set, at the amount the customer actually chose.
     *
     * <p>Every recorded amount moves with it, in proportion. That is not a trick to make the
     * numbers line up: the quantities in these scenarios are proportional in reality too, since a
     * partial capture is a fraction of an authorization and a processing fee is a percentage of a
     * capture. Leaving the hops at the scenario amount would put a timeline on screen that
     * contradicts the total above it.
     *
     * <p>A scaled quantity that rounds away to nothing is held at the smallest unit instead.
     * A shortfall of zero is a different payment from a shortfall too small to print.
     */
    private JsonNode scaledTo(JsonNode template, long amountMinor) {
        double factor = (double) amountMinor / template.get("amountMinor").asLong();
        ObjectNode scaled = template.deepCopy();
        scaled.put("amountMinor", amountMinor);

        for (JsonNode hop : scaled.get("hops")) {
            ObjectNode node = (ObjectNode) hop;
            scale(node, "amountMinor", factor);
            if (node.get("attrs") instanceof ObjectNode attrs) {
                attrs.fieldNames().forEachRemaining(field -> {
                    if (field.endsWith("Minor")) {
                        scale(attrs, field, factor);
                    }
                });
            }
            // The dataset keeps these on the hop itself until the loader files them into attrs.
            for (String field : new String[] {"expectedMinor", "actualMinor", "deltaMinor"}) {
                scale(node, field, factor);
            }
        }
        return scaled;
    }

    private void scale(ObjectNode node, String field, double factor) {
        if (!node.hasNonNull(field)) {
            return;
        }
        long original = node.get(field).asLong();
        long value = Math.round(original * factor);
        node.put(field, original != 0 && value == 0 ? 1 : value);
    }

    private static String reference() {
        byte[] bytes = new byte[3];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }
}
