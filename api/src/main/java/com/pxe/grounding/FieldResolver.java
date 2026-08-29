package com.pxe.grounding;

import com.pxe.model.PaymentHop;
import com.pxe.model.PaymentReference;
import com.pxe.timeline.Timeline;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * G3, and the mechanism behind G4.
 *
 * <p>A placeholder names a path to a typed field on this payment. Resolving it is what puts a
 * number into a sentence, and the number can only come from the record, which is the whole point:
 * the model never typed it, so it cannot have mistyped it.
 *
 * <p>Paths look like {@code hops[3].amountMinor}, {@code hops[4].attrs.deltaMinor} or
 * {@code references[1].value}. Index is the position in the causal order the model was shown.
 * Field names are matched leniently across snake_case and camelCase, because the wire has both and
 * a rendering should not fail over a naming convention.
 */
public final class FieldResolver {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss").withLocale(Locale.UK)
                    .withZone(ZoneOffset.UTC);

    private final Timeline timeline;

    public FieldResolver(Timeline timeline) {
        this.timeline = timeline;
    }

    /** The rendered value of a path, or empty when nothing on this payment answers to it. */
    public Optional<String> resolve(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String[] parts = path.trim().split("\\.");
        if (parts.length < 2) {
            return Optional.empty();
        }
        String head = parts[0];

        if (head.startsWith("hops[")) {
            return index(head).flatMap(this::hop).flatMap(h -> hopField(h, parts));
        }
        if (head.startsWith("references[")) {
            return index(head).flatMap(this::reference).flatMap(r -> referenceField(r, parts));
        }
        return Optional.empty();
    }

    /** Which hop a path points at, so G3 can check the claim actually cited it. */
    public Optional<Integer> hopSeqOf(String path) {
        if (path == null || !path.trim().startsWith("hops[")) {
            return Optional.empty();
        }
        return index(path.trim().split("\\.")[0]).flatMap(this::hop).map(PaymentHop::getSeq);
    }

    private Optional<Integer> index(String head) {
        int open = head.indexOf('[');
        int close = head.indexOf(']');
        if (open < 0 || close < open) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(head.substring(open + 1, close)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Optional<PaymentHop> hop(int i) {
        return i >= 0 && i < timeline.hops().size()
                ? Optional.of(timeline.hops().get(i))
                : Optional.empty();
    }

    private Optional<PaymentReference> reference(int i) {
        return i >= 0 && i < timeline.references().size()
                ? Optional.of(timeline.references().get(i))
                : Optional.empty();
    }

    private Optional<String> hopField(PaymentHop hop, String[] parts) {
        String field = normalise(parts[1]);

        if ("attrs".equals(field)) {
            if (parts.length < 3) {
                return Optional.empty();
            }
            Map<String, Object> attrs = hop.getAttrs();
            String key = parts[2];
            Object value = attrs.containsKey(key) ? attrs.get(key) : attrs.get(normalise(key));
            return Optional.ofNullable(value).map(v -> format(normalise(key), v));
        }

        Object value = switch (field) {
            case "seq" -> hop.getSeq();
            case "stage" -> hop.getStage();
            case "actor" -> hop.getActor();
            case "status" -> hop.getStatus();
            case "code" -> hop.getCode();
            case "latencyms" -> hop.getLatencyMs();
            case "occurredat", "at" -> hop.getOccurredAt();
            case "amountminor", "amount" -> hop.getAmountMinor();
            case "batch" -> hop.getBatch();
            case "cycle" -> hop.getCycle();
            case "cutoffat" -> hop.getCutoffAt();
            case "boundreference" -> hop.getBoundReference();
            case "note" -> hop.getNote();
            default -> null;
        };
        return Optional.ofNullable(value).map(v -> format(field, v));
    }

    private Optional<String> referenceField(PaymentReference ref, String[] parts) {
        String field = normalise(parts[1]);
        Object value = switch (field) {
            case "value" -> ref.getValue();
            case "kind" -> ref.getKind().name();
            case "hopseq" -> ref.getHopSeq();
            case "validfrom" -> ref.getValidFrom();
            default -> null;
        };
        return Optional.ofNullable(value).map(v -> format(field, v));
    }

    /** snake_case, camelCase and SCREAMING_CASE all arrive; none of them should decide the outcome. */
    private static String normalise(String field) {
        return field.replace("_", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Minor units become money and instants become readable time. A rendered number has to match
     * the ledger, which means it has to be formatted from the ledger rather than transcribed.
     */
    private String format(String field, Object value) {
        if (value instanceof Instant instant) {
            return STAMP.format(instant);
        }
        if (field.endsWith("minor") && value instanceof Number number) {
            return String.format(Locale.UK, "%,.2f", number.doubleValue() / 100.0);
        }
        if (field.equals("latencyms") && value instanceof Number number) {
            return String.format(Locale.UK, "%,d ms", number.longValue());
        }
        return String.valueOf(value);
    }
}
