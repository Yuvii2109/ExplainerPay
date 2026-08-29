package com.pxe.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxe.model.Instrument;
import com.pxe.model.Payment;
import com.pxe.model.PaymentHop;
import com.pxe.model.PaymentHopRepository;
import com.pxe.model.PaymentReference;
import com.pxe.model.PaymentReferenceRepository;
import com.pxe.model.PaymentRepository;
import com.pxe.model.ReferenceKind;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads data/payment-scenarios.json into the L0 tables.
 *
 * <p>Only recorded fact is loaded. The {@code expected}, {@code explanation}, {@code injectedCause}
 * and {@code demoNote} blocks of each scenario are golden data and are deliberately never written
 * to the database: nothing downstream of ingestion should be able to reach ground truth by
 * querying. The eval harness of phase 3 reads them from the file instead.
 */
@Component
@Order(10)
public class ScenarioLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScenarioLoader.class);

    /** The dataset has one merchant. G2 needs a payment to belong to one. */
    public static final String DEMO_MERCHANT = "MERCH-DEMO-001";

    /**
     * Hop fields that become typed columns or a reference row. Every other field in a hop object
     * lands in {@code attrs}, so a field added to the dataset is carried rather than dropped.
     */
    private static final Set<String> MAPPED_HOP_FIELDS = Set.of(
            "seq", "stage", "actor", "at", "status", "code", "latencyMs", "retryOf", "duplicateOf",
            "amountMinor", "batch", "cycle", "cutoffAt", "missedCutoff", "included",
            "boundReference", "note", "reference");

    private final PaymentRepository payments;
    private final PaymentHopRepository hops;
    private final PaymentReferenceRepository references;
    private final ObjectMapper mapper;
    private final Resource scenarios;

    public ScenarioLoader(PaymentRepository payments, PaymentHopRepository hops,
                          PaymentReferenceRepository references, ObjectMapper mapper,
                          @Value("${pxe.scenarios-resource}") Resource scenarios) {
        this.payments = payments;
        this.hops = hops;
        this.references = references;
        this.mapper = mapper;
        this.scenarios = scenarios;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        load();
    }

    /**
     * Loads the dataset if the payments table is empty, and does nothing otherwise. To force a
     * reload, destroy the volume: {@code docker compose down -v}.
     */
    @Transactional
    public void load() throws IOException {
        if (payments.count() > 0) {
            log.info("scenarios already loaded: {} payments, {} hops, {} references",
                    payments.count(), hops.count(), references.count());
            return;
        }

        JsonNode document;
        try (InputStream in = scenarios.getInputStream()) {
            document = mapper.readTree(in);
        }

        for (JsonNode scenario : document.get("scenarios")) {
            loadScenario(scenario);
        }

        log.info("loaded scenarios from {}: {} payments, {} hops, {} references",
                scenarios.getDescription(), payments.count(), hops.count(), references.count());
    }

    private void loadScenario(JsonNode scenario) {
        String id = scenario.get("id").asText();
        JsonNode hopNodes = scenario.get("hops");

        payments.save(new Payment(
                id,
                DEMO_MERCHANT,
                scenario.get("amountMinor").asLong(),
                scenario.get("currency").asText(),
                Instrument.valueOf(scenario.get("instrument").asText()),
                scenario.get("rail").asText(),
                firstOccurrence(hopNodes)));

        List<PaymentReference> loaded = new ArrayList<>();
        for (JsonNode hop : hopNodes) {
            hops.save(toHop(id, hop));
            if (hop.hasNonNull("reference")) {
                JsonNode ref = hop.get("reference");
                loaded.add(references.save(new PaymentReference(
                        id,
                        hop.get("seq").asInt(),
                        ReferenceKind.valueOf(ref.get("kind").asText()),
                        ref.get("value").asText(),
                        instantOrNull(hop, "at"))));
            }
        }
        linkSupersessions(loaded);
    }

    private PaymentHop toHop(String paymentId, JsonNode hop) {
        return new PaymentHop(
                paymentId,
                hop.get("seq").asInt(),
                hop.get("stage").asText(),
                hop.get("actor").asText(),
                instantOrNull(hop, "at"),
                hop.get("status").asText(),
                textOrNull(hop, "code"),
                longOrNull(hop, "latencyMs"),
                intOrNull(hop, "retryOf"),
                intOrNull(hop, "duplicateOf"),
                longOrNull(hop, "amountMinor"),
                textOrNull(hop, "batch"),
                textOrNull(hop, "cycle"),
                instantOrNull(hop, "cutoffAt"),
                boolOrNull(hop, "missedCutoff"),
                boolOrNull(hop, "included"),
                textOrNull(hop, "boundReference"),
                textOrNull(hop, "note"),
                unmappedFields(hop));
    }

    /**
     * Within a kind and in hop order, a reference is superseded by the next one carrying a
     * different value. PXE-006 links its two RRNs; PXE-009 repeats one UTR and links nothing.
     */
    private void linkSupersessions(List<PaymentReference> loaded) {
        for (ReferenceKind kind : ReferenceKind.values()) {
            List<PaymentReference> ofKind = loaded.stream().filter(r -> r.getKind() == kind).toList();
            for (int i = 0; i < ofKind.size() - 1; i++) {
                PaymentReference earlier = ofKind.get(i);
                PaymentReference later = ofKind.get(i + 1);
                if (!earlier.getValue().equals(later.getValue())) {
                    earlier.markSupersededBy(later);
                    references.save(earlier);
                }
            }
        }
    }

    private Map<String, Object> unmappedFields(JsonNode hop) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        Iterator<String> names = hop.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!MAPPED_HOP_FIELDS.contains(name)) {
                attrs.put(name, mapper.convertValue(hop.get(name), Object.class));
            }
        }
        return attrs;
    }

    private Instant firstOccurrence(JsonNode hopNodes) {
        for (JsonNode hop : hopNodes) {
            Instant at = instantOrNull(hop, "at");
            if (at != null) {
                return at;
            }
        }
        throw new IllegalStateException("scenario has no hop with a timestamp");
    }

    private static Instant instantOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? Instant.parse(node.get(field).asText()) : null;
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Long longOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asLong() : null;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asInt() : null;
    }

    private static Boolean boolOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asBoolean() : null;
    }
}
