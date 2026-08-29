package com.pxe.model;

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
 * A reference is a row, not a column on the payment.
 *
 * <p>Reference mutation across a retry is why scenario PXE-006 is representable at all.
 * {@code supersededBy} points at the reference that replaced this one. It is set only when the
 * replacement carries a different value, so a duplicate callback repeating the same UTR supersedes
 * nothing. Supersession is computed within a kind: an RRN never supersedes a UTR.
 */
@Entity
@Table(name = "payment_references")
public class PaymentReference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private String paymentId;

    @Column(name = "hop_seq", nullable = false)
    private int hopSeq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReferenceKind kind;

    @Column(nullable = false)
    private String value;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "superseded_by")
    private Long supersededBy;

    protected PaymentReference() {
    }

    public PaymentReference(String paymentId, int hopSeq, ReferenceKind kind, String value,
                            Instant validFrom) {
        this.paymentId = paymentId;
        this.hopSeq = hopSeq;
        this.kind = kind;
        this.value = value;
        this.validFrom = validFrom;
    }

    public Long getId() {
        return id;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public int getHopSeq() {
        return hopSeq;
    }

    public ReferenceKind getKind() {
        return kind;
    }

    public String getValue() {
        return value;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Long getSupersededBy() {
        return supersededBy;
    }

    public void markSupersededBy(PaymentReference replacement) {
        this.supersededBy = replacement.getId();
    }
}
