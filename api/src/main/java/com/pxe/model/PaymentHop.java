package com.pxe.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One recorded hop. This is L0: the record, and the only rung that cannot be wrong.
 *
 * <p>{@code occurredAt} is null when the hop did not happen, which is the absent node of reference
 * section 19. {@code attrs} carries the low-frequency per-stage fields of the dataset so that no
 * field is silently dropped on load; everything a rule in section 12 predicates on is a typed
 * column above it, never a key inside the blob.
 */
@Entity
@Table(name = "payment_hops")
@IdClass(PaymentHop.Key.class)
public class PaymentHop {

    @Id
    @Column(name = "payment_id")
    private String paymentId;

    @Id
    private int seq;

    @Column(nullable = false)
    private String stage;

    @Column(nullable = false)
    private String actor;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(nullable = false)
    private String status;

    private String code;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "retry_of")
    private Integer retryOf;

    @Column(name = "duplicate_of")
    private Integer duplicateOf;

    @Column(name = "amount_minor")
    private Long amountMinor;

    private String batch;

    private String cycle;

    @Column(name = "cutoff_at")
    private Instant cutoffAt;

    @Column(name = "missed_cutoff")
    private Boolean missedCutoff;

    private Boolean included;

    @Column(name = "bound_reference")
    private String boundReference;

    private String note;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> attrs;

    protected PaymentHop() {
    }

    public PaymentHop(String paymentId, int seq, String stage, String actor, Instant occurredAt,
                      String status, String code, Long latencyMs, Integer retryOf,
                      Integer duplicateOf, Long amountMinor, String batch, String cycle,
                      Instant cutoffAt, Boolean missedCutoff, Boolean included,
                      String boundReference, String note, Map<String, Object> attrs) {
        this.paymentId = paymentId;
        this.seq = seq;
        this.stage = stage;
        this.actor = actor;
        this.occurredAt = occurredAt;
        this.status = status;
        this.code = code;
        this.latencyMs = latencyMs;
        this.retryOf = retryOf;
        this.duplicateOf = duplicateOf;
        this.amountMinor = amountMinor;
        this.batch = batch;
        this.cycle = cycle;
        this.cutoffAt = cutoffAt;
        this.missedCutoff = missedCutoff;
        this.included = included;
        this.boundReference = boundReference;
        this.note = note;
        this.attrs = attrs;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public int getSeq() {
        return seq;
    }

    public String getStage() {
        return stage;
    }

    public String getActor() {
        return actor;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public Integer getRetryOf() {
        return retryOf;
    }

    public Integer getDuplicateOf() {
        return duplicateOf;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public String getBatch() {
        return batch;
    }

    public String getCycle() {
        return cycle;
    }

    public Instant getCutoffAt() {
        return cutoffAt;
    }

    public Boolean getMissedCutoff() {
        return missedCutoff;
    }

    public Boolean getIncluded() {
        return included;
    }

    public String getBoundReference() {
        return boundReference;
    }

    public String getNote() {
        return note;
    }

    public Map<String, Object> getAttrs() {
        return attrs;
    }

    /** Composite key: a hop is identified by its payment and its sequence number. */
    public static class Key implements Serializable {

        private String paymentId;
        private int seq;

        public Key() {
        }

        public Key(String paymentId, int seq) {
            this.paymentId = paymentId;
            this.seq = seq;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return seq == other.seq && Objects.equals(paymentId, other.paymentId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(paymentId, seq);
        }
    }
}
