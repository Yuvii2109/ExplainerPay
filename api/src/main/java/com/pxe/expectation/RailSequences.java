package com.pxe.expectation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * The happy-path stage sequence of each rail, from data/rail-sequences.json.
 *
 * <p>This is what section 17.3 pre-reserves: the skeleton is rendered greyed at intent creation and
 * each row lights as its event lands, so cumulative layout shift is zero by construction rather
 * than by measurement.
 *
 * <p>It is keyed by rail and not by instrument, because UPI_QR, UPI_COLLECT and UPI_PAYOUT share an
 * instrument and have almost no stages in common. A row that never lights is the absent node of
 * section 19; an event that is on no happy path appends below everything already drawn.
 */
@Component
public class RailSequences {

    private static final Logger log = LoggerFactory.getLogger(RailSequences.class);

    private final Map<String, List<String>> byRail;

    public RailSequences(ObjectMapper mapper,
                         @Value("${pxe.rail-sequences-resource}") Resource resource)
            throws IOException {
        try (InputStream in = resource.getInputStream()) {
            this.byRail = Map.copyOf(mapper.readValue(in, new TypeReference<>() {
            }));
        }
        log.info("loaded rail sequences from {}: {} rails", resource.getDescription(), byRail.size());
    }

    /** The stages to draw before anything has happened. Empty for a rail we do not model. */
    public List<String> skeletonFor(String rail) {
        return byRail.getOrDefault(rail, List.of());
    }

    public Map<String, List<String>> all() {
        return byRail;
    }
}
