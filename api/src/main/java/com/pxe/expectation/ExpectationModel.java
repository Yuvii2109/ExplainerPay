package com.pxe.expectation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxe.model.Instrument;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** The expectation model, loaded from data/expectations.json at startup. */
@Component
public class ExpectationModel {

    private static final Logger log = LoggerFactory.getLogger(ExpectationModel.class);

    private final List<Expectation> rows;

    public ExpectationModel(ObjectMapper mapper,
                            @Value("${pxe.expectations-resource}") Resource resource)
            throws IOException {
        try (InputStream in = resource.getInputStream()) {
            this.rows = List.of(mapper.readValue(in, Expectation[].class));
        }
        log.info("loaded expectation model from {}: {} rows", resource.getDescription(), rows.size());
    }

    public List<Expectation> rows() {
        return rows;
    }

    /** The settlement cycle for an instrument. Absent for NETBANKING, deliberately. */
    public Optional<Expectation> settlement(Instrument instrument) {
        return rows.stream()
                .filter(r -> r.instrument() == instrument && Expectation.SETTLEMENT.equals(r.stage()))
                .findFirst();
    }

    /**
     * The row that expected this stage to follow it, used to find the SLA for an event that has
     * not occurred. UPI {@code PAYOUT} lists {@code PAYOUT_CREDITED}, which is how PXE-014 gets a
     * six-hour SLA without a stage-name mapping table.
     */
    public Optional<Expectation> slaForStageThatDidNotOccur(Instrument instrument, String hopStage) {
        return rows.stream()
                .filter(r -> r.instrument() == instrument && r.slaMs() != null && r.listsAsNextStage(hopStage))
                .findFirst();
    }
}
