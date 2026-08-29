package com.pxe.ingest;

import com.pxe.model.Payment;
import com.pxe.model.PaymentHopRepository;
import com.pxe.model.PaymentReferenceRepository;
import com.pxe.model.PaymentRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What actually landed in the database, read back from it. This is the observable form of the
 * phase 1 exit criterion.
 */
@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final PaymentRepository payments;
    private final PaymentHopRepository hops;
    private final PaymentReferenceRepository references;

    public ScenarioController(PaymentRepository payments, PaymentHopRepository hops,
                              PaymentReferenceRepository references) {
        this.payments = payments;
        this.hops = hops;
        this.references = references;
    }

    @GetMapping("/report")
    public Report report() {
        List<Row> rows = payments.findAll().stream()
                .sorted(Comparator.comparing(Payment::getId))
                .map(p -> new Row(
                        p.getId(),
                        p.getInstrument().name(),
                        p.getRail(),
                        p.getAmountMinor(),
                        hops.countByPaymentId(p.getId()),
                        references.countByPaymentId(p.getId())))
                .toList();

        return new Report(rows.size(), hops.count(), references.count(), rows);
    }

    public record Row(String paymentId, String instrument, String rail, long amountMinor,
                      long hops, long references) {
    }

    public record Report(int payments, long hops, long references, List<Row> rows) {
    }
}
