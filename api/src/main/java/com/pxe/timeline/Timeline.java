package com.pxe.timeline;

import com.pxe.model.Payment;
import com.pxe.model.PaymentHop;
import com.pxe.model.PaymentReference;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * L1: the reconstructed timeline. Hops in causal order with their references attached.
 *
 * <p>It cannot be wrong, only incomplete. Nothing here interprets: the accessors below are the
 * vocabulary the L2 detectors read the record through, so a detector reads as its predicate in
 * section 9.1 rather than as a loop.
 */
public record Timeline(Payment payment, List<PaymentHop> hops, List<PaymentReference> references) {

    public static Timeline of(Payment payment, List<PaymentHop> hops,
                              List<PaymentReference> references) {
        return new Timeline(
                payment,
                hops.stream().sorted(Comparator.comparingInt(PaymentHop::getSeq)).toList(),
                references.stream().sorted(Comparator.comparingInt(PaymentReference::getHopSeq)).toList());
    }

    public List<PaymentHop> at(String stage) {
        return hops.stream().filter(h -> stage.equals(h.getStage())).toList();
    }

    public boolean has(String stage) {
        return hops.stream().anyMatch(h -> stage.equals(h.getStage()));
    }

    public boolean hasAny(String... stages) {
        return List.of(stages).stream().anyMatch(this::has);
    }

    public Optional<PaymentHop> first(String stage) {
        return at(stage).stream().findFirst();
    }

    /** A hop that reached a given status at a given stage, for example a debit that succeeded. */
    public boolean succeededAt(String stage) {
        return hops.stream().anyMatch(h -> stage.equals(h.getStage()) && "OK".equals(h.getStatus()));
    }

    /** A stage whose event has not occurred carries no timestamp. Section 19 draws it. */
    public boolean occurred(String stage) {
        return hops.stream().anyMatch(h -> stage.equals(h.getStage()) && h.getOccurredAt() != null);
    }

    public List<PaymentHop> notOccurred() {
        return hops.stream().filter(h -> h.getOccurredAt() == null).toList();
    }

    public Map<String, List<PaymentHop>> byStage() {
        Map<String, List<PaymentHop>> grouped = new LinkedHashMap<>();
        hops.forEach(h -> grouped.computeIfAbsent(h.getStage(), k -> new java.util.ArrayList<>()).add(h));
        return grouped;
    }

    public Optional<Instant> occurredAtOf(String stage) {
        return at(stage).stream().map(PaymentHop::getOccurredAt).filter(java.util.Objects::nonNull).findFirst();
    }

    /** The amount recorded at a stage, when that stage records one. */
    public Optional<Long> amountAt(String stage) {
        return at(stage).stream().map(PaymentHop::getAmountMinor).filter(java.util.Objects::nonNull).findFirst();
    }

    /** The last authorized amount, which the card flow may record on either auth hop. */
    public Optional<Long> authorizedAmount() {
        return hops.reversed().stream()
                .filter(h -> "NETWORK_AUTH".equals(h.getStage()) || "AUTH_REQUEST".equals(h.getStage()))
                .map(PaymentHop::getAmountMinor)
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }
}
