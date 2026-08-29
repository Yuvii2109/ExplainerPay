package com.pxe.expectation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.pxe.model.Instrument;
import java.util.List;

/**
 * One row of data/expectations.json. Expectation is data, not code (reference section 9).
 *
 * <p>Only three of the eight {@code stage} values are hop stages. See section 9.2 for how a row is
 * matched to a hop: by stage, by {@code nextStages} for an event that has not occurred, or by
 * instrument alone in the case of {@code SETTLEMENT}, which is a cycle rather than a stage.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Expectation(
        Instrument instrument,
        String stage,
        Long slaMs,
        List<String> nextStages,
        String cycle,
        String creditByLocal,
        String timezone) {

    public static final String SETTLEMENT = "SETTLEMENT";

    public boolean listsAsNextStage(String hopStage) {
        return nextStages != null && nextStages.contains(hopStage);
    }

    /** The N of a {@code T+N} settlement cycle. */
    public int cycleDays() {
        return Integer.parseInt(cycle.substring(cycle.indexOf('+') + 1));
    }
}
