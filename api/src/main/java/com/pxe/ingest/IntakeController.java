package com.pxe.ingest;

import com.pxe.payable.Payable;
import com.pxe.payable.Payables;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Taking a payment in. The only route that creates one.
 *
 * <p>The merchant arms which failure the rails return; the customer chooses the amount. Neither
 * decision belongs to the other, which is also true of the thing being modelled.
 */
@RestController
public class IntakeController {

    private final PaymentIntake intake;
    private final ArmedScenario armed;
    private final com.pxe.model.Merchants merchants;
    private final com.pxe.model.PaymentRepository payments;
    private final Payables payables;

    public IntakeController(PaymentIntake intake, ArmedScenario armed,
                            com.pxe.model.Merchants merchants,
                            com.pxe.model.PaymentRepository payments, Payables payables) {
        this.intake = intake;
        this.armed = armed;
        this.merchants = merchants;
        this.payments = payments;
        this.payables = payables;
    }

    /**
     * Who you can pay, and what each of them is still owed an answer about.
     *
     * <p>The exposure is unexplained money, not money a customer owes. Nobody settles an
     * explanation debt by paying it; the figure is here because it is the most useful thing to
     * know about a merchant before you look at their payments.
     */
    @GetMapping("/api/merchants")
    public List<Payee> merchants() {
        return merchants.all().stream().map(m -> {
            var owed = payments.findByDebtOpenTrueOrderByAmountMinorDesc().stream()
                    .filter(p -> m.id().equals(p.getMerchantId()))
                    .toList();
            var outstanding = payables.openFor(m.id());
            return new Payee(m.id(), m.name(), m.category(), owed.size(),
                    owed.stream().mapToLong(com.pxe.model.Payment::getAmountMinor).sum(),
                    outstanding.size(),
                    outstanding.stream().mapToLong(Payable::getRemainingMinor).sum());
        }).toList();
    }

    /**
     * A merchant on the checkout list.
     *
     * <p>Two independent numbers, and conflating them would be the whole product misunderstood.
     * {@code owedMinor} is money that has not reached them yet and paying it makes it go away.
     * {@code exposureMinor} is money whose fate nobody can account for, and paying does nothing to
     * it at all.
     */
    public record Payee(String id, String name, String category, int unexplained,
                        long exposureMinor, int outstanding, long owedMinor) {
    }

    /** What is still owed, oldest due date first. Optionally for one merchant. */
    @GetMapping("/api/payables")
    public List<Owed> payables(
            @RequestParam(value = "merchant", required = false) String merchantId) {
        List<Payable> rows = merchantId == null ? payables.open() : payables.openFor(merchantId);
        LocalDate today = LocalDate.now();
        return rows.stream()
                .map(p -> new Owed(p.getId(), p.getMerchantId(), merchants.name(p.getMerchantId()),
                        p.getDescription(), p.getDueOn().toString(), p.getCurrency(),
                        p.getAmountMinor(), p.getRemainingMinor(),
                        p.getDueOn().isBefore(today),
                        p.getRemainingMinor() < p.getAmountMinor(), p.getLastPaymentId()))
                .toList();
    }

    /**
     * {@code part} marks a row a payment reached without closing. That is the interesting state:
     * money moved, the rails said it succeeded, and the merchant is still short.
     */
    public record Owed(String id, String merchantId, String merchantName, String description,
                       String dueOn, String currency, long amountMinor, long remainingMinor,
                       boolean overdue, boolean part, String lastPaymentId) {
    }

    /** Back to what was owed at the start. Payments and their debts are left alone. */
    @PostMapping("/api/payables/reset")
    public ResponseEntity<Void> resetPayables() throws IOException {
        payables.reset();
        return ResponseEntity.noContent().build();
    }

    /** What the checkout is about to become, so the phone can show it before anyone pays. */
    @GetMapping("/api/pay/armed")
    public Armed armed() {
        return new Armed(armed.get());
    }

    @PostMapping("/api/pay/arm")
    public Armed arm(@RequestParam("as") String scenarioId) {
        armed.arm(scenarioId);
        return new Armed(armed.get());
    }

    @PostMapping("/api/pay")
    public ResponseEntity<PaymentIntake.Taken> pay(
            @RequestParam(value = "as", required = false) String scenarioId,
            @RequestParam(value = "amountMinor", required = false) Long amountMinor,
            @RequestParam(value = "merchant", required = false) String merchantId,
            @RequestParam(value = "payable", required = false) String payableId)
            throws IOException {
        try {
            return ResponseEntity.ok(intake.take(
                    scenarioId == null ? armed.get() : scenarioId, amountMinor, merchantId,
                    payableId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    public record Armed(String scenario) {
    }
}
