package com.pxe.payable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxe.model.OutcomeTag;
import com.pxe.model.Payment;
import com.pxe.model.PaymentHop;
import com.pxe.model.PaymentHopRepository;
import com.pxe.model.PaymentRepository;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payables queue of section 8.1, and the one rule that moves it.
 *
 * <p>A payable is credited by what the merchant was actually paid, which is the amount on the hop
 * where money reached them. Not the authorized amount, not the captured amount, not the amount the
 * customer typed. Those three can all agree while the merchant is short, and when they do, the row
 * staying open is the system telling the truth about it.
 *
 * <p>Nothing here writes to the timeline or to a deviation. It reads the resolved outcome and the
 * hops and updates one column, so no explanation can be bent by what somebody owes.
 */
@Component
@Order(5)
public class Payables implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Payables.class);

    /**
     * The hops on which money reaches the merchant. Two, because the card rails and the UPI rails
     * name the same event differently and a payables queue that only understood one of them would
     * silently never settle half the dataset.
     */
    private static final Set<String> CREDITED = Set.of("PAYOUT_CREDITED", "PAYEE_CREDIT");

    /**
     * Outcomes on which money is the merchant's to keep. Everything else leaves the payable alone,
     * which is the behaviour worth being strict about: a declined card, a switch timeout and a
     * payout that never landed all look like a payment was attempted, and none of them is one that
     * arrived.
     */
    private static final Set<OutcomeTag> CLEARED =
            Set.of(OutcomeTag.SUCCESS, OutcomeTag.DEEMED_SUCCESS);

    private record Seed(String id, String merchantId, String description, LocalDate dueOn,
                        long amountMinor) {
    }

    private final PayableRepository payables;
    private final PaymentRepository payments;
    private final PaymentHopRepository hops;
    private final ObjectMapper mapper;
    private final Resource resource;

    public Payables(PayableRepository payables, PaymentRepository payments,
                    PaymentHopRepository hops, ObjectMapper mapper,
                    @Value("${pxe.payables-resource}") Resource resource) {
        this.payables = payables;
        this.payments = payments;
        this.hops = hops;
        this.mapper = mapper;
        this.resource = resource;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        load();
    }

    /** Seeds if the table is empty and does nothing otherwise, matching how scenarios load. */
    @Transactional
    public void load() throws IOException {
        if (payables.count() > 0) {
            return;
        }
        payables.saveAll(read().stream()
                .map(s -> new Payable(s.id(), s.merchantId(), s.description(), s.dueOn(), "INR",
                        s.amountMinor()))
                .toList());
        log.info("loaded {} payables from {}", payables.count(), resource.getDescription());
    }

    /**
     * Back to what was owed at the start, keeping every payment that was taken in.
     *
     * <p>A demo gets run more than once and the queue is the part of it that does not reset by
     * itself. Rebuilding the rows rather than deleting the payments keeps the two axes honest: the
     * money owed goes back, the explanation debt does not, because nothing that happened stopped
     * having happened.
     */
    @Transactional
    public int reset() throws IOException {
        payables.deleteAll();
        payables.flush();
        List<Payable> fresh = read().stream()
                .map(s -> new Payable(s.id(), s.merchantId(), s.description(), s.dueOn(), "INR",
                        s.amountMinor()))
                .toList();
        payables.saveAll(fresh);
        log.info("reset {} payables", fresh.size());
        return fresh.size();
    }

    public List<Payable> open() {
        return payables.findBySettledAtIsNullOrderByDueOnAsc();
    }

    public List<Payable> openFor(String merchantId) {
        return payables.findByMerchantIdAndSettledAtIsNullOrderByDueOnAsc(merchantId);
    }

    /** What a merchant is still owed in total, across every open row. */
    public long owed(String merchantId) {
        return openFor(merchantId).stream().mapToLong(Payable::getRemainingMinor).sum();
    }

    /**
     * Apply a payment to a payable.
     *
     * <p>Returns what the merchant was credited, which is zero when the payment failed, when it is
     * still pending, or when the settlement hop never arrived. Zero leaves the row exactly as it
     * was, which is the behaviour that matters: a payment that did not land does not reduce what is
     * owed just because somebody pressed pay.
     */
    @Transactional
    public long apply(String payableId, String paymentId) {
        Optional<Payable> found = payables.findById(payableId);
        if (found.isEmpty()) {
            return 0;
        }
        long credited = creditedTo(paymentId);
        found.get().credit(credited, paymentId, Instant.now());
        log.info("payment {} credited {} minor against payable {}", paymentId, credited, payableId);
        return credited;
    }

    /**
     * What reached the merchant on this payment, read off the resolved outcome and the timeline.
     *
     * <p>Two conditions, and both have to hold. The payment has to have cleared, because a decline,
     * a timeout and a payout still pending are all payments that were attempted and none of them is
     * a payment that arrived. Then the money is the amount on the <em>last</em> settlement hop that
     * happened, since a card payment credits the payee bank before the merchant's own bank and only
     * the second one is the merchant being paid.
     *
     * <p>A settlement hop that carries no amount is not a settlement of nothing. The dataset states
     * an amount on that hop only when it differs from the payment, so silence there means the full
     * amount arrived. Reading it as zero would have made every clean payment look like a failure.
     */
    public long creditedTo(String paymentId) {
        Payment payment = payments.findById(paymentId).orElse(null);
        if (payment == null || !CLEARED.contains(payment.getTag())) {
            return 0;
        }
        Optional<PaymentHop> arrival = hops.findByPaymentIdOrderBySeqAsc(paymentId).stream()
                .filter(h -> CREDITED.contains(h.getStage()))
                // A hop with no time on it is the absent node of section 19. Nothing arrived.
                .filter(h -> h.getOccurredAt() != null)
                .reduce((first, second) -> second);
        if (arrival.isEmpty()) {
            return 0;
        }
        Long stated = arrival.get().getAmountMinor();
        return stated != null ? stated : payment.getAmountMinor();
    }

    private List<Seed> read() throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return List.of(mapper.readValue(in, Seed[].class));
        }
    }
}
