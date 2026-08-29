package com.pxe.rules;

import com.pxe.model.PaymentHop;
import com.pxe.model.PaymentReference;
import com.pxe.timeline.Timeline;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * L3a-ii. The eight rules of section 12, each a pure predicate over the hop sequence.
 *
 * <p>A rule is added only after its scenario exists in the golden set. That ordering is
 * non-negotiable rule 8, and it is why there are eight rules and not nine: every one of them names
 * a failure mode the dataset already contains.
 *
 * <p>Rules are tried in order and the first match wins. No two currently overlap on any scenario,
 * which the test asserts, so the order is a tie-break that is never used rather than a hidden
 * priority.
 */
@Component
public class RuleCatalogue {

    /**
     * A fired rule: which rule, the cause it names, and the hops it matched on. The hops become the
     * citations, so an attribution can always be traced back to the record that produced it.
     */
    public record Match(String ruleId, String rootCause, List<Integer> hops) {

        public List<String> citations() {
            return java.util.stream.Stream.concat(
                    hops.stream().map(seq -> "hop:" + seq),
                    java.util.stream.Stream.of("rule:" + ruleId)).toList();
        }
    }

    private record Rule(String id, String rootCause, Function<Timeline, List<Integer>> match) {
    }

    private final List<Rule> rules = List.of(
            new Rule("RECON_RRN_MUTATION", "RRN_MUTATION_ON_RETRY", RuleCatalogue::rrnMutation),
            new Rule("SETTLEMENT_CUTOFF_MISSED", "BANK_CUTOFF_MISSED_WEEKEND", RuleCatalogue::cutoffMissed),
            new Rule("AUTH_CAPTURE_DRIFT", "PARTIAL_CAPTURE_DRIFT", RuleCatalogue::authCaptureDrift),
            new Rule("IDEMPOTENT_DUPLICATE", "DUPLICATE_CALLBACK", RuleCatalogue::idempotentDuplicate),
            new Rule("BENEFICIARY_DOWN_AUTOREVERSE", "BENEFICIARY_BANK_UNAVAILABLE",
                    RuleCatalogue::beneficiaryDownAutoreverse),
            new Rule("SOFT_DECLINE_RECOVERED", "ISSUER_SOFT_DECLINE_RETRY_SUCCESS",
                    RuleCatalogue::softDeclineRecovered),
            new Rule("LATE_CALLBACK_RECONCILED", "LATE_CALLBACK_AFTER_SESSION_CLOSE",
                    RuleCatalogue::lateCallbackReconciled),
            new Rule("INTENT_EXPIRED_UNUSED", "INTENT_EXPIRED_NO_ACTION", RuleCatalogue::intentExpiredUnused));

    public List<String> ruleIds() {
        return rules.stream().map(Rule::id).toList();
    }

    /** The first rule that fires, if any. Nothing here reaches a model. */
    public Optional<Match> firstMatch(Timeline timeline) {
        for (Rule rule : rules) {
            List<Integer> hops = rule.match().apply(timeline);
            if (!hops.isEmpty()) {
                return Optional.of(new Match(rule.id(), rule.rootCause(), hops));
            }
        }
        return Optional.empty();
    }

    /** Every rule that would fire, used only to assert that the catalogue does not overlap. */
    public List<String> allMatches(Timeline timeline) {
        return rules.stream()
                .filter(r -> !r.match().apply(timeline).isEmpty())
                .map(Rule::id)
                .toList();
    }

    /**
     * The settlement selector bound a reference that a retry superseded, so the batch closed
     * without a payment that had in fact succeeded. Captured, owed, and invisible to the batch.
     */
    private static List<Integer> rrnMutation(Timeline t) {
        Optional<PaymentReference> superseded = t.references().stream()
                .filter(r -> r.getSupersededBy() != null)
                .findFirst();
        Optional<PaymentHop> bound = t.hops().stream()
                .filter(h -> h.getBoundReference() != null)
                .findFirst();
        Optional<PaymentHop> excluded = t.hops().stream()
                .filter(h -> Boolean.FALSE.equals(h.getIncluded()))
                .findFirst();
        boolean retried = t.hops().stream().anyMatch(h -> h.getRetryOf() != null);

        if (superseded.isEmpty() || bound.isEmpty() || excluded.isEmpty() || !retried) {
            return List.of();
        }
        if (!bound.get().getBoundReference().equals(superseded.get().getValue())) {
            return List.of();
        }
        return List.of(superseded.get().getHopSeq(), bound.get().getSeq(), excluded.get().getSeq());
    }

    /** Landed the wrong side of the cut-off and rolled into a later cycle. */
    private static List<Integer> cutoffMissed(Timeline t) {
        return t.hops().stream()
                .filter(h -> Boolean.TRUE.equals(h.getMissedCutoff()))
                .map(PaymentHop::getSeq)
                .toList();
    }

    /** Captured less than was authorized. Legitimate, and it still owes the merchant an answer. */
    private static List<Integer> authCaptureDrift(Timeline t) {
        Optional<Long> authorized = t.authorizedAmount();
        Optional<Long> captured = t.amountAt("CAPTURED");
        if (authorized.isEmpty() || captured.isEmpty() || captured.get() >= authorized.get()) {
            return List.of();
        }
        return t.hops().stream()
                .filter(h -> "CAPTURED".equals(h.getStage()) || "NETWORK_AUTH".equals(h.getStage()))
                .map(PaymentHop::getSeq)
                .toList();
    }

    /** The bank said it twice. The guard caught the second one before the ledger. */
    private static List<Integer> idempotentDuplicate(Timeline t) {
        return t.hops().stream()
                .filter(h -> h.getDuplicateOf() != null)
                .flatMap(h -> java.util.stream.Stream.of(h.getDuplicateOf(), h.getSeq()))
                .toList();
    }

    /** Debited, the beneficiary bank was unreachable, and the network is putting it back. */
    private static List<Integer> beneficiaryDownAutoreverse(Timeline t) {
        Optional<PaymentHop> debit = t.at("PAYER_DEBIT").stream()
                .filter(h -> "OK".equals(h.getStatus()))
                .findFirst();
        Optional<PaymentHop> credit = t.at("PAYEE_CREDIT").stream()
                .filter(h -> "U28".equals(h.getCode()) || "BT".equals(h.getCode()))
                .findFirst();
        Optional<PaymentHop> reversal = t.first("AUTO_REVERSAL_INITIATED");
        if (debit.isEmpty() || credit.isEmpty() || reversal.isEmpty()) {
            return List.of();
        }
        return List.of(debit.get().getSeq(), credit.get().getSeq(), reversal.get().getSeq());
    }

    /**
     * The issuer was briefly unavailable and the retry cascade got through. The customer may have
     * seen two failures before the success, and there is only one charge.
     */
    private static List<Integer> softDeclineRecovered(Timeline t) {
        List<Integer> softDeclines = t.hops().stream()
                .filter(h -> "DECLINED".equals(h.getStatus()) && "91".equals(h.getCode()))
                .map(PaymentHop::getSeq)
                .toList();
        Optional<PaymentHop> recovered = t.hops().stream()
                .filter(h -> "OK".equals(h.getStatus()) && "00".equals(h.getCode())
                        && h.getRetryOf() != null)
                .findFirst();
        if (softDeclines.isEmpty() || recovered.isEmpty()) {
            return List.of();
        }
        return java.util.stream.Stream.concat(softDeclines.stream(),
                java.util.stream.Stream.of(recovered.get().getSeq())).toList();
    }

    /** The bank confirmed after we had given up and shown the customer a failure. */
    private static List<Integer> lateCallbackReconciled(Timeline t) {
        Optional<PaymentHop> abandoned = t.first("SESSION_ABANDONED");
        Optional<PaymentHop> reconciled = t.at("STATE_RECONCILED").stream().findFirst();
        if (abandoned.isEmpty() || reconciled.isEmpty()) {
            return List.of();
        }
        Optional<PaymentHop> callback = t.at("CALLBACK_RECEIVED").stream()
                .filter(h -> h.getOccurredAt() != null
                        && h.getOccurredAt().isAfter(abandoned.get().getOccurredAt()))
                .findFirst();
        return callback
                .map(hop -> List.of(abandoned.get().getSeq(), hop.getSeq(), reconciled.get().getSeq()))
                .orElseGet(List::of);
    }

    /** Nobody ever scanned it. There is no customer-side failure to explain. */
    private static List<Integer> intentExpiredUnused(Timeline t) {
        Optional<PaymentHop> created = t.first("INTENT_CREATED");
        Optional<PaymentHop> expired = t.first("INTENT_EXPIRED");
        if (created.isEmpty() || expired.isEmpty() || t.has("QR_SCANNED")) {
            return List.of();
        }
        return List.of(created.get().getSeq(), expired.get().getSeq());
    }
}
