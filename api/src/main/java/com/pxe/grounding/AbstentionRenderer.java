package com.pxe.grounding;

import com.pxe.explain.AiClient;
import com.pxe.model.PaymentHop;
import com.pxe.timeline.Timeline;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

/**
 * The rendering of an absence.
 *
 * <p>"Cannot be determined" is not the absence of an explanation; it is one. Section 19: naming the
 * missing thing precisely <em>is</em> the answer, and a screen that says only "cause not
 * determinable" has named nothing. What the reader needs is what we asked for, what came back, what
 * did not, how long it has been, and which causes the evidence refuses to separate.
 *
 * <p>Built from the record rather than from a template or a model, because an abstention must
 * render on any rail and must never depend on a generation that could itself be rejected. Nothing
 * here can be wrong: every number comes from a typed field.
 */
@Component
public class AbstentionRenderer {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withLocale(Locale.UK).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMMM").withLocale(Locale.UK).withZone(ZoneOffset.UTC);

    public Optional<AudienceRenderer.Rendering> render(Timeline timeline,
                                                       List<AiClient.Candidate> considered) {
        Optional<PaymentHop> missing = timeline.notOccurred().stream().findFirst();
        if (missing.isEmpty()) {
            return Optional.empty();
        }
        PaymentHop absent = missing.get();
        Optional<PaymentHop> lastHeard = timeline.hops().stream()
                .filter(h -> h.getOccurredAt() != null)
                .reduce((a, b) -> b);

        String stage = readable(absent.getStage());
        String actor = readable(absent.getActor());
        String waited = waited(absent);
        String amount = String.format(Locale.UK, "%,.2f %s",
                timeline.payment().getAmountMinor() / 100.0, timeline.payment().getCurrency());

        // Merchant prose never names a stage. "We asked your bank to payout credited" is what
        // happens when an internal symbol is asked to be a verb, and it reads like a system talking
        // to itself rather than to the person whose money is missing.
        StringJoiner merchant = new StringJoiner(" ");
        merchant.add("We are waiting on your bank for " + amount + ".");
        lastHeard.ifPresent(h -> merchant.add("The last thing they sent us was at "
                + TIME.format(h.getOccurredAt()) + " on " + DATE.format(h.getOccurredAt()) + ","));
        merchant.add("and nothing has come back since" + waited + ".");
        merchant.add("We are not going to guess at a reason, because from what we can see there "
                + "is nothing to read. We have escalated it and we will tell you the moment your "
                + "bank tells us anything.");

        StringJoiner support = new StringJoiner(" ");
        support.add("The " + stage + " never arrived from the " + actor + waited + ".");
        support.add("We genuinely do not know the cause: there is no event to read.");
        support.add("Do NOT offer the merchant a reason, and do not tell them it will complete.");
        support.add("Escalate to the banking team with the payment reference, and tell the "
                + "merchant exactly what we have asked for and when we expect an answer.");
        if (!considered.isEmpty()) {
            support.add("Causes ruled neither in nor out: " + causeList(considered) + ".");
        }

        StringJoiner engineer = new StringJoiner(" ");
        lastHeard.ifPresent(h -> engineer.add(h.getStage() + " at " + TIME.format(h.getOccurredAt())
                + " is the last recorded event."));
        engineer.add(absent.getStage() + " is absent" + waited + ".");
        engineer.add("No further event from " + absent.getActor() + " of any kind.");
        if (considered.isEmpty()) {
            engineer.add("Nothing in the record separates the candidate mechanisms.");
        } else {
            engineer.add(considered.size() + " causes are consistent with this evidence and none is "
                    + "distinguishable from it: " + causeList(considered) + ".");
        }
        engineer.add("Correct output is abstention. Any confident attribution here is a false "
                + "attribution.");

        return Optional.of(new AudienceRenderer.Rendering(
                merchant.toString(), support.toString(), engineer.toString()));
    }

    /** How long we have been waiting, from the record, or nothing when the record does not say. */
    private String waited(PaymentHop absent) {
        Object elapsed = absent.getAttrs().get("elapsedHours");
        Object sla = absent.getAttrs().get("slaHours");
        if (!(elapsed instanceof Number e)) {
            return "";
        }
        return sla instanceof Number s
                ? ", now " + e.longValue() + " hours past a " + s.longValue() + " hour window"
                : ", " + e.longValue() + " hours ago";
    }

    private String causeList(List<AiClient.Candidate> considered) {
        return considered.stream()
                .map(c -> readable(c.cause()))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    /** PAYOUT_CREDITED reads badly in a sentence meant for a merchant. */
    private String readable(String symbol) {
        return symbol == null ? "" : symbol.replace('_', ' ').toLowerCase(Locale.UK);
    }
}
