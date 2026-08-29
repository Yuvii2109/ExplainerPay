package com.pxe.grounding;

import com.pxe.model.PaymentHop;
import com.pxe.model.PaymentReference;
import com.pxe.timeline.Timeline;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The named values a per-cause template is allowed to ask for.
 *
 * <p>The deterministic paths render from templates, not from a model, but they obey the same rule:
 * the template writes {@code {capturedAmount}} and never a number. Every slot is resolved from the
 * record here, so a rendered figure on the CODE and RULE paths is as traceable as one the validator
 * substituted on the MODEL path — and just as impossible to mistype.
 *
 * <p>Slots are semantic rather than positional. A template cannot say {@code hops[3]} because the
 * same cause appears at different positions in different payments.
 */
public final class Slots {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMMM").withLocale(Locale.UK).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withLocale(Locale.UK).withZone(ZoneOffset.UTC);

    private Slots() {
    }

    public static Map<String, String> of(Timeline t) {
        Map<String, String> slots = new LinkedHashMap<>();

        put(slots, "amount", money(t.payment().getAmountMinor()));
        put(slots, "currency", t.payment().getCurrency());
        put(slots, "code", t.payment().getResponseCode());

        t.authorizedAmount().ifPresent(v -> put(slots, "authorizedAmount", money(v)));
        t.amountAt("CAPTURED").ifPresent(v -> put(slots, "capturedAmount", money(v)));
        t.amountAt("PAYOUT_CREDITED").ifPresent(v -> put(slots, "settledAmount", money(v)));
        t.amountAt("AUTH_RESIDUAL_RELEASED").ifPresent(v -> put(slots, "residualAmount", money(v)));
        t.amountAt("PAYOUT_INSTRUCTED").ifPresent(v -> put(slots, "instructedAmount", money(v)));

        t.occurredAtOf("PAYOUT_CREDITED").ifPresent(v -> put(slots, "payoutDate", DATE.format(v)));
        t.occurredAtOf("AUTH_RESIDUAL_RELEASED")
                .ifPresent(v -> put(slots, "residualReleasedDate", DATE.format(v)));
        t.occurredAtOf("SETTLEMENT_SCHEDULED").ifPresent(v -> {
            put(slots, "scheduledDate", DATE.format(v));
            put(slots, "scheduledTime", TIME.format(v));
        });
        t.occurredAtOf("SESSION_ABANDONED").ifPresent(v -> put(slots, "abandonedTime", TIME.format(v)));
        t.occurredAtOf("INTENT_EXPIRED").ifPresent(v -> put(slots, "expiredTime", TIME.format(v)));
        t.occurredAtOf("STATE_RECONCILED").ifPresent(v -> put(slots, "reconciledTime", TIME.format(v)));
        t.occurredAtOf("AUTO_REVERSAL_INITIATED")
                .ifPresent(v -> put(slots, "reversalTime", TIME.format(v)));

        // The late callback is the one that landed after we had already given up.
        t.occurredAtOf("SESSION_ABANDONED").ifPresent(abandoned -> t.at("CALLBACK_RECEIVED").stream()
                .filter(h -> h.getOccurredAt() != null && h.getOccurredAt().isAfter(abandoned))
                .findFirst()
                .ifPresent(h -> put(slots, "callbackTime", TIME.format(h.getOccurredAt()))));

        first(t, h -> h.getBatch() != null).ifPresent(h -> put(slots, "batch", h.getBatch()));
        first(t, h -> h.getCycle() != null).ifPresent(h -> put(slots, "cycle", h.getCycle()));
        first(t, h -> h.getCutoffAt() != null)
                .ifPresent(h -> put(slots, "cutoffTime", TIME.format(h.getCutoffAt())));

        t.references().stream().filter(r -> r.getSupersededBy() != null).findFirst()
                .ifPresent(old -> {
                    put(slots, "referenceSuperseded", old.getValue());
                    put(slots, "referenceKind", old.getKind().name());
                    t.references().stream()
                            .filter(r -> r.getKind() == old.getKind()
                                    && r.getHopSeq() > old.getHopSeq())
                            .findFirst()
                            .ifPresent(now -> put(slots, "referenceActive", now.getValue()));
                });
        t.references().stream().reduce((a, b) -> b)
                .map(PaymentReference::getValue)
                .ifPresent(v -> put(slots, "reference", v));

        attr(t, "deltaMinor").ifPresent(v -> put(slots, "shortfallAmount", money(asLong(v))));
        attr(t, "expectedMinor").ifPresent(v -> put(slots, "expectedAmount", money(asLong(v))));
        attr(t, "actualMinor").ifPresent(v -> put(slots, "creditedAmount", money(asLong(v))));
        attr(t, "slaHours").ifPresent(v -> put(slots, "slaHours", String.valueOf(v)));
        attr(t, "elapsedHours").ifPresent(v -> put(slots, "elapsedHours", String.valueOf(v)));
        attr(t, "lateBy").ifPresent(v -> put(slots, "lateBy", String.valueOf(v)));

        // How many times we asked before it worked. Three auth attempts is three network fees.
        t.byStage().values().stream()
                .filter(g -> g.size() >= 2 && g.stream().anyMatch(h -> h.getRetryOf() != null))
                .findFirst()
                .ifPresent(g -> put(slots, "attempts", String.valueOf(g.size())));

        return slots;
    }

    private static Optional<PaymentHop> first(Timeline t, java.util.function.Predicate<PaymentHop> p) {
        return t.hops().stream().filter(p).findFirst();
    }

    private static Optional<Object> attr(Timeline t, String key) {
        return t.hops().stream()
                .map(h -> h.getAttrs().get(key))
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String money(long minor) {
        return String.format(Locale.UK, "%,.2f", minor / 100.0);
    }

    private static void put(Map<String, String> slots, String name, String value) {
        if (value != null) {
            slots.put(name, value);
        }
    }

    /** Unused, but it keeps the formatter honest about what a stamp looks like. */
    static String stamp(Instant instant) {
        return DATE.format(instant) + " " + TIME.format(instant);
    }
}
