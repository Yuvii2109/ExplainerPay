package com.pxe.payable;

import static org.assertj.core.api.Assertions.assertThat;

import com.pxe.ingest.PaymentIntake;
import com.pxe.support.Baseline;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Section 8.1. What a payment does to what is owed.
 *
 * <p>Every case here is a claim about one thing: money owed comes down by what the merchant was
 * credited, not by what the customer was charged. The two agree on a clean payment and they are
 * the whole story when they do not.
 *
 * <p>Integration test. Requires the compose stack: {@code docker compose up -d}.
 */
@SpringBootTest
@ActiveProfiles("test")
class PayableTest {

    @Autowired
    Payables payables;
    @Autowired
    PayableRepository repository;
    @Autowired
    PaymentIntake intake;
    @Autowired
    Baseline baseline;

    /** A bill belonging to the merchant that owns the scenario being paid with. */
    private static final String NILGIRI_BILL = "OWE-1011";

    @BeforeEach
    void owedInFull() {
        baseline.deterministicOnly();
    }

    @Test
    void theQueueLoadsFromTheFileAndStartsOwedInFull() {
        List<Payable> open = payables.open();

        assertThat(open).hasSize(20);
        assertThat(open).allSatisfy(p ->
                assertThat(p.getRemainingMinor()).isEqualTo(p.getAmountMinor()));
        assertThat(open).extracting(Payable::getMerchantId).doesNotContainNull();
    }

    @Test
    void theQueueIsOldestDueDateFirst() {
        assertThat(payables.open()).isSortedAccordingTo(
                (a, b) -> a.getDueOn().compareTo(b.getDueOn()));
    }

    @Test
    void aCleanPaymentSettlesTheBillAndLeavesTheQueueShorter() throws Exception {
        Payable bill = repository.findById(NILGIRI_BILL).orElseThrow();
        long owed = bill.getRemainingMinor();

        PaymentIntake.Taken taken =
                intake.take("PXE-001", owed, "MERCH-NILGIRI", NILGIRI_BILL);

        assertThat(taken.creditedMinor()).isEqualTo(owed);
        Payable after = repository.findById(NILGIRI_BILL).orElseThrow();
        assertThat(after.getRemainingMinor()).isZero();
        assertThat(after.getSettledAt()).isNotNull();
        assertThat(after.getLastPaymentId()).isEqualTo(taken.paymentId());
        assertThat(payables.open()).hasSize(19).extracting(Payable::getId)
                .doesNotContain(NILGIRI_BILL);
    }

    @Test
    void aReconciliationBreakLeavesTheBillOpenForTheDifference() throws Exception {
        // PXE-012 charges the customer in full and credits the merchant less, because a processing
        // fee was applied twice in one batch. The rails call it a success. The bill disagrees, and
        // the amount it is still short by is the size of the problem.
        Payable bill = repository.findById(NILGIRI_BILL).orElseThrow();
        long owed = bill.getRemainingMinor();

        PaymentIntake.Taken taken =
                intake.take("PXE-012", owed, "MERCH-NILGIRI", NILGIRI_BILL);

        assertThat(taken.creditedMinor())
                .as("the merchant was credited less than the customer paid")
                .isLessThan(owed)
                .isPositive();

        Payable after = repository.findById(NILGIRI_BILL).orElseThrow();
        assertThat(after.getRemainingMinor()).isEqualTo(owed - taken.creditedMinor());
        assertThat(after.getSettledAt()).isNull();
        assertThat(payables.open()).extracting(Payable::getId).contains(NILGIRI_BILL);
    }

    @Test
    void aDeclinedPaymentLeavesTheBillExactlyAsItWas() throws Exception {
        Payable bill = repository.findById(NILGIRI_BILL).orElseThrow();
        long owed = bill.getRemainingMinor();

        PaymentIntake.Taken taken =
                intake.take("PXE-004", owed, "MERCH-NILGIRI", NILGIRI_BILL);

        assertThat(taken.creditedMinor()).isZero();
        Payable after = repository.findById(NILGIRI_BILL).orElseThrow();
        assertThat(after.getRemainingMinor()).isEqualTo(owed);
        assertThat(after.getSettledAt()).isNull();
        assertThat(after.getLastPaymentId()).isNull();
    }

    @Test
    void aPayoutThatNeverLandedCreditsNothing() throws Exception {
        // PXE-014 is the absent node: the payout was instructed, acknowledged, and never credited.
        // Nothing arrived, so nothing is discharged, however successful the earlier hops look.
        Payable bill = repository.findById(NILGIRI_BILL).orElseThrow();
        long owed = bill.getRemainingMinor();

        PaymentIntake.Taken taken =
                intake.take("PXE-014", owed, "MERCH-NILGIRI", NILGIRI_BILL);

        assertThat(taken.creditedMinor()).isZero();
        assertThat(repository.findById(NILGIRI_BILL).orElseThrow().getRemainingMinor())
                .isEqualTo(owed);
    }

    @Test
    void payingPartOfABillLeavesTheRest() throws Exception {
        Payable bill = repository.findById(NILGIRI_BILL).orElseThrow();
        long owed = bill.getRemainingMinor();
        long half = owed / 2;

        intake.take("PXE-001", half, "MERCH-NILGIRI", NILGIRI_BILL);

        Payable after = repository.findById(NILGIRI_BILL).orElseThrow();
        assertThat(after.getRemainingMinor()).isEqualTo(owed - half);
        assertThat(after.getSettledAt()).isNull();
    }

    @Test
    void overpayingSettlesTheBillWithoutOwingTheDifferenceBack() throws Exception {
        // This system holds no balances. Paying more than a bill closes it and stops there, which
        // is the boring answer and the only one that cannot go negative.
        Payable bill = repository.findById(NILGIRI_BILL).orElseThrow();
        long owed = bill.getRemainingMinor();

        intake.take("PXE-001", owed * 2, "MERCH-NILGIRI", NILGIRI_BILL);

        Payable after = repository.findById(NILGIRI_BILL).orElseThrow();
        assertThat(after.getRemainingMinor()).isZero();
        assertThat(after.getSettledAt()).isNotNull();
    }

    @Test
    void aPaymentAgainstNoBillChangesNothingInTheQueue() throws Exception {
        List<Long> before = payables.open().stream().map(Payable::getRemainingMinor).toList();

        PaymentIntake.Taken taken = intake.take("PXE-001", 100_000L, "MERCH-NILGIRI", null);

        assertThat(taken.creditedMinor()).isZero();
        assertThat(payables.open()).extracting(Payable::getRemainingMinor)
                .containsExactlyElementsOf(before);
    }

    @Test
    void settlingLateStillSettles() throws Exception {
        // PXE-007 arrives after the deadline. The money did reach the merchant, so the bill is
        // discharged. The explanation debt it opens is a separate axis and is asserted elsewhere.
        Payable bill = repository.findById(NILGIRI_BILL).orElseThrow();
        long owed = bill.getRemainingMinor();

        PaymentIntake.Taken taken =
                intake.take("PXE-007", owed, "MERCH-NILGIRI", NILGIRI_BILL);

        assertThat(taken.creditedMinor()).isEqualTo(owed);
        assertThat(repository.findById(NILGIRI_BILL).orElseThrow().getSettledAt()).isNotNull();
    }
}
