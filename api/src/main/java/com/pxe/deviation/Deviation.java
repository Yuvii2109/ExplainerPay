package com.pxe.deviation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * L2. A departure from what the expectation model said should happen.
 *
 * <p>The table also carries {@code expected}, {@code actual} and {@code severity}. They stay null
 * until a consumer exists: the console of phase 5 is the first thing that renders them, and a
 * detector that fills them now would be guessing at the format that screen wants.
 */
@Entity
@Table(name = "deviations")
public class Deviation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private String paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviationType type;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    protected Deviation() {
    }

    public Deviation(String paymentId, DeviationType type, Instant detectedAt) {
        this.paymentId = paymentId;
        this.type = type;
        this.detectedAt = detectedAt;
    }

    public Long getId() {
        return id;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public DeviationType getType() {
        return type;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }
}
