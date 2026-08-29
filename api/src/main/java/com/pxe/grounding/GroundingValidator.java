package com.pxe.grounding;

import com.pxe.explain.AiClient;
import com.pxe.model.PaymentHop;
import com.pxe.rules.RuleCatalogue;
import com.pxe.timeline.Timeline;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Section 15. G1 to G9, applied to a generated response before any of it is believed.
 *
 * <p>Groundedness is enforced here rather than hoped for. Two kinds of failure: a claim that cannot
 * be supported is <em>dropped</em>, and a response that breaks the protocol is <em>rejected</em>
 * whole. G4 is the second kind, and it is the one to dwell on: a literal digit is not a content
 * error, it is the model doing something it was structurally forbidden from doing.
 */
@Component
public class GroundingValidator {

    /** G4. Any digit at all. The rule is absolute so that it cannot be argued with at the margin. */
    private static final Pattern DIGIT = Pattern.compile("\\d");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    /**
     * G7. Phrases that assert the money arrived. Checked against whether it did.
     *
     * <p>Deliberately a short list of unambiguous assertions rather than an attempt at
     * comprehension: a narrow check that fires correctly is worth more than a broad one that fires
     * on hedged language.
     */
    private static final List<String> SETTLEMENT_ASSERTIONS = List.of(
            "has been settled", "was settled", "have been settled", "has been credited to you",
            "has been paid to you", "you have been paid", "you have been credited",
            "the payout completed", "settled to you");

    private final RuleCatalogue rules;

    public GroundingValidator(RuleCatalogue rules) {
        this.rules = rules;
    }

    /**
     * Validates the claims of a hypothesis, resolving every surviving placeholder from typed
     * fields. The returned text is what may be shown; the input text never is.
     */
    public Grounding validate(Timeline timeline, List<AiClient.Claim> claims, String rootCause,
                              Set<String> taxonomy, List<String> renderings) {
        FieldResolver resolver = new FieldResolver(timeline);

        // G9: a cause outside the taxonomy is not a cause this system can name.
        if (rootCause != null && !taxonomy.contains(rootCause)) {
            return Grounding.reject("G9", "root cause " + rootCause + " is not in the taxonomy");
        }

        // G4: applied to everything the model wrote, claims and renderings alike, before anything
        // else is considered. A protocol violation is not worth analysing further.
        for (AiClient.Claim claim : claims) {
            if (containsLiteralNumber(claim.text())) {
                return Grounding.reject("G4",
                        "claim " + claim.id() + " contains a literal number: " + claim.text());
            }
        }
        for (String rendering : renderings) {
            if (containsLiteralNumber(rendering)) {
                return Grounding.reject("G4", "a rendering contains a literal number: "
                        + firstDigitContext(rendering));
            }
        }

        // G7: the explanation may not assert a terminal status the record contradicts.
        boolean settled = timeline.occurred("PAYOUT_CREDITED");
        if (!settled) {
            for (String rendering : renderings) {
                Optional<String> asserted = assertsSettlement(rendering);
                if (asserted.isPresent()) {
                    return Grounding.reject("G7", "says " + asserted.get()
                            + " but no payout was credited");
                }
            }
        }

        List<Grounding.Claim> kept = new ArrayList<>();
        List<Grounding.Dropped> dropped = new ArrayList<>();
        int rendered = 0;
        int matching = 0;

        for (AiClient.Claim claim : claims) {
            String kind = claim.kind();

            // G1 and G2: every citation resolves to a record of this payment.
            Optional<String> badCitation = claim.citations().stream()
                    .filter(c -> !resolves(timeline, c))
                    .findFirst();
            if (badCitation.isPresent()) {
                dropped.add(new Grounding.Dropped(claim.id(), "G1",
                        "citation " + badCitation.get() + " resolves to nothing on this payment"));
                continue;
            }

            // G6: a hypothesis must cite a rule or at least two hops.
            long hopCitations = claim.citations().stream().filter(c -> c.startsWith("hop:")).count();
            boolean citesRule = claim.citations().stream().anyMatch(c -> c.startsWith("rule:"));
            if ("HYPOTHESIS".equals(kind) && !citesRule && hopCitations < 2) {
                dropped.add(new Grounding.Dropped(claim.id(), "G6",
                        "a hypothesis needs a rule or two hops; it cites " + claim.citations()));
                continue;
            }

            // G5: a claim standing only on a rule id is not entailed by the record. It is a
            // restatement of the rule, which is a proposal about this payment, not a fact of it.
            if ("FACT".equals(kind) && hopCitations == 0) {
                kind = "HYPOTHESIS";
            }

            // G3: every placeholder resolves to a typed field on a cited record.
            Map<String, String> placeholders = claim.placeholders() == null
                    ? Map.of() : claim.placeholders();
            String text = claim.text();
            boolean resolvedAll = true;

            var matcher = PLACEHOLDER.matcher(claim.text());
            while (matcher.find()) {
                String name = matcher.group(1);
                String path = placeholders.get(name);
                Optional<String> value = path == null ? Optional.empty() : resolver.resolve(path);
                if (value.isEmpty()) {
                    dropped.add(new Grounding.Dropped(claim.id(), "G3",
                            "placeholder {" + name + "} resolves to no typed field"));
                    resolvedAll = false;
                    break;
                }
                Optional<Integer> seq = resolver.hopSeqOf(path);
                if (seq.isPresent() && !claim.citations().contains("hop:" + seq.get())) {
                    dropped.add(new Grounding.Dropped(claim.id(), "G3",
                            "placeholder {" + name + "} reads hop:" + seq.get()
                                    + ", which the claim does not cite"));
                    resolvedAll = false;
                    break;
                }
                rendered++;
                matching++;
                text = text.replace("{" + name + "}", value.get());
            }
            if (!resolvedAll) {
                continue;
            }

            kept.add(new Grounding.Claim(claim.id(), kind, text, claim.citations()));
        }

        return new Grounding(false, null, null, kept, dropped, rendered, matching);
    }

    /** Substitutes an audience rendering, or empty if any placeholder cannot be resolved. */
    public Optional<String> render(Timeline timeline, String text, Map<String, String> placeholders) {
        if (text == null) {
            return Optional.empty();
        }
        FieldResolver resolver = new FieldResolver(timeline);
        String rendered = text;
        var matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            String path = placeholders == null ? null : placeholders.get(name);
            Optional<String> value = path == null ? Optional.empty() : resolver.resolve(path);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            rendered = rendered.replace("{" + name + "}", value.get());
        }
        return Optional.of(rendered);
    }

    /** G1 and G2 together: does this citation name a record, and is it this payment's? */
    private boolean resolves(Timeline timeline, String citation) {
        if (citation == null || !citation.contains(":")) {
            return false;
        }
        String kind = citation.substring(0, citation.indexOf(':'));
        String value = citation.substring(citation.indexOf(':') + 1);

        return switch (kind) {
            case "hop" -> timeline.hops().stream()
                    .anyMatch(h -> String.valueOf(h.getSeq()).equals(value));
            case "ref" -> timeline.references().stream()
                    .anyMatch(r -> String.valueOf(r.getHopSeq()).equals(value)
                            || r.getValue().equals(value));
            case "rule" -> rules.ruleIds().contains(value);
            case "code" -> timeline.hops().stream()
                    .map(PaymentHop::getCode)
                    .anyMatch(value::equals);
            default -> false;
        };
    }

    /**
     * A digit outside a placeholder. {@code {amount_1}} is a slot, not a number: the model is
     * naming a field it wants filled, which is the behaviour the rule exists to encourage. Checking
     * the raw string would reject the very convention the prompt teaches.
     */
    private static boolean containsLiteralNumber(String text) {
        return text != null && DIGIT.matcher(PLACEHOLDER.matcher(text).replaceAll("")).find();
    }

    private Optional<String> assertsSettlement(String rendering) {
        if (rendering == null) {
            return Optional.empty();
        }
        String lower = rendering.toLowerCase(Locale.ROOT);
        return SETTLEMENT_ASSERTIONS.stream().filter(lower::contains).findFirst();
    }

    private static String firstDigitContext(String text) {
        var matcher = DIGIT.matcher(PLACEHOLDER.matcher(text).replaceAll(""));
        if (!matcher.find()) {
            return text;
        }
        int from = Math.max(0, matcher.start() - 30);
        int to = Math.min(text.length(), matcher.start() + 30);
        return "..." + text.substring(from, to) + "...";
    }
}
