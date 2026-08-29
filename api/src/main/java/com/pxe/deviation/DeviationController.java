package com.pxe.deviation;

import com.pxe.model.Payment;
import com.pxe.model.PaymentRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The computed L2 deviation set, read back from the database. */
@RestController
@RequestMapping("/api")
public class DeviationController {

    private final PaymentRepository payments;
    private final DeviationRepository deviations;

    public DeviationController(PaymentRepository payments, DeviationRepository deviations) {
        this.payments = payments;
        this.deviations = deviations;
    }

    @GetMapping("/payments/{id}/deviations")
    public List<String> forPayment(@PathVariable String id) {
        return typesOf(id);
    }

    @GetMapping("/deviations/report")
    public Report report() {
        List<Row> rows = payments.findAll().stream()
                .sorted(Comparator.comparing(Payment::getId))
                .map(p -> new Row(p.getId(), typesOf(p.getId())))
                .toList();
        long deviating = rows.stream().filter(r -> !r.deviations().isEmpty()).count();
        return new Report(rows.size(), deviating, deviations.count(), rows);
    }

    private List<String> typesOf(String paymentId) {
        return deviations.findByPaymentIdOrderByTypeAsc(paymentId).stream()
                .map(d -> d.getType().name())
                .toList();
    }

    public record Row(String paymentId, List<String> deviations) {
    }

    public record Report(int payments, long deviating, long deviations, List<Row> rows) {
    }
}
