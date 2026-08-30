package com.pxe.ingest;

import java.io.IOException;
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

    public IntakeController(PaymentIntake intake, ArmedScenario armed,
                            com.pxe.model.Merchants merchants,
                            com.pxe.model.PaymentRepository payments) {
        this.intake = intake;
        this.armed = armed;
        this.merchants = merchants;
        this.payments = payments;
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
            return new Payee(m.id(), m.name(), m.category(), owed.size(),
                    owed.stream().mapToLong(com.pxe.model.Payment::getAmountMinor).sum());
        }).toList();
    }

    public record Payee(String id, String name, String category, int unexplained,
                        long exposureMinor) {
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
            @RequestParam(value = "merchant", required = false) String merchantId)
            throws IOException {
        try {
            return ResponseEntity.ok(intake.take(
                    scenarioId == null ? armed.get() : scenarioId, amountMinor, merchantId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    public record Armed(String scenario) {
    }
}
