package com.pxe.explain;

import com.pxe.deviation.DeviationType;
import com.pxe.model.PaymentHop;
import com.pxe.model.PaymentReference;
import com.pxe.timeline.Timeline;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * Section 16. The content address of everything an explanation is allowed to be about.
 *
 * <p>The same payment in the same state produces the same hash, and an explanation whose inputs
 * have not changed is never regenerated. Without this the system cannot be tested, and an
 * explanation that cannot be reproduced is not evidence.
 *
 * <p>The digest covers L0 and L2 and nothing else. It deliberately excludes anything the pipeline
 * produced downstream — no root cause, no path, no text — so the hash identifies the question
 * rather than the answer.
 */
public final class FactSetHash {

    private FactSetHash() {
    }

    public static String of(Timeline t, Collection<DeviationType> deviations) {
        StringJoiner facts = new StringJoiner("\n");
        facts.add("payment:%s|%d|%s|%s|%s".formatted(
                t.payment().getId(),
                t.payment().getAmountMinor(),
                t.payment().getCurrency(),
                t.payment().getInstrument(),
                t.payment().getRail()));

        for (PaymentHop h : t.hops()) {
            facts.add("hop:%d|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s".formatted(
                    h.getSeq(), h.getStage(), h.getActor(), h.getOccurredAt(), h.getStatus(),
                    h.getCode(), h.getLatencyMs(), h.getRetryOf(), h.getDuplicateOf(),
                    h.getAmountMinor(), h.getBatch(), h.getCycle(), h.getCutoffAt(),
                    h.getMissedCutoff(), h.getIncluded(), h.getBoundReference()));
        }

        for (PaymentReference r : t.references()) {
            facts.add("ref:%d|%s|%s|%s".formatted(
                    r.getHopSeq(), r.getKind(), r.getValue(), r.getSupersededBy() != null));
        }

        // Sorted, because a deviation set is a set and detection order must not change the address.
        new TreeSet<>(deviations).forEach(d -> facts.add("deviation:" + d));

        return sha256(facts.toString());
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
