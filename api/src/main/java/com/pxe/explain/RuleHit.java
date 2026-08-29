package com.pxe.explain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A rule fired. Recorded rather than inferred, so "show me the rule that explained this" is a query
 * and not an argument. Demo beat 6 puts it on screen.
 */
@Entity
@Table(name = "rule_hits")
public class RuleHit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private String paymentId;

    @Column(name = "rule_id", nullable = false)
    private String ruleId;

    @Column(name = "matched_at", nullable = false)
    private Instant matchedAt;

    /** The hops the predicate matched on. These become the citations of the explanation. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String inputs;

    protected RuleHit() {
    }

    public RuleHit(String paymentId, String ruleId, Instant matchedAt, String inputs) {
        this.paymentId = paymentId;
        this.ruleId = ruleId;
        this.matchedAt = matchedAt;
        this.inputs = inputs;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getRuleId() {
        return ruleId;
    }

    public Instant getMatchedAt() {
        return matchedAt;
    }

    public String getInputs() {
        return inputs;
    }
}
