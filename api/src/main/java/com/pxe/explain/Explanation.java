package com.pxe.explain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A produced explanation. Durable, versioned, replayable.
 *
 * <p>Every explanation records its {@code factSetHash} and, when a model was involved, its
 * {@code promptVersion}. Without both it cannot be reproduced, and an explanation that cannot be
 * reproduced is not evidence.
 *
 * <p>The three audience texts are null on the deterministic paths at this phase. L3 is a cause with
 * citations; L4 is the wording, and the renderers arrive with the phase that owns them. The
 * distinction matters: an attribution that is right and unworded is still an explanation, and a
 * wording without an attribution is a plausible sentence about someone's money.
 */
@Entity
@Table(name = "explanations")
public class Explanation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private String paymentId;

    /** The rung reached. L3 is a cause; L4 adds the narrative. */
    @Column(nullable = false)
    private String level;

    /** NONE, CODE, RULE, MODEL or ABSTAIN. How it was produced, and the funnel test. */
    @Column(nullable = false)
    private String path;

    @Column(name = "fact_set_hash", nullable = false)
    private String factSetHash;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "root_cause")
    private String rootCause;

    @Column(nullable = false)
    private boolean determinable;

    private BigDecimal confidence;

    @Column(nullable = false)
    private boolean hypothesis;

    @Column(nullable = false)
    private boolean abstained;

    @JdbcTypeCode(SqlTypes.JSON)
    private String claims;

    @JdbcTypeCode(SqlTypes.JSON)
    private String citations;

    @Column(name = "merchant_text")
    private String merchantText;

    @Column(name = "support_text")
    private String supportText;

    @Column(name = "engineer_text")
    private String engineerText;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    protected Explanation() {
    }

    /** A deterministic attribution: a cause the rails or a rule established, with its citations. */
    public static Explanation determined(String paymentId, String path, String factSetHash,
                                         String rootCause, String citations, Instant generatedAt,
                                         String merchantText, String supportText,
                                         String engineerText) {
        Explanation explanation = new Explanation();
        explanation.paymentId = paymentId;
        explanation.level = "L3";
        explanation.path = path;
        explanation.factSetHash = factSetHash;
        explanation.rootCause = rootCause;
        explanation.determinable = true;
        explanation.confidence = BigDecimal.ONE;
        explanation.hypothesis = false;
        explanation.abstained = false;
        explanation.citations = citations;
        explanation.merchantText = merchantText;
        explanation.supportText = supportText;
        explanation.engineerText = engineerText;
        // L4 once it has been said in words; L3 when the record could not fill the template.
        explanation.level = merchantText == null ? "L3" : "L4";
        explanation.generatedAt = generatedAt;
        return explanation;
    }

    /**
     * A cause the model proposed, or its refusal to propose one. Always marked: a hypothesis
     * presented as a finding is the failure mode the whole architecture exists to prevent.
     */
    public static Explanation fromModel(String paymentId, String factSetHash, String promptVersion,
                                        String rootCause, boolean determinable, Double confidence,
                                        String claims, String citations, String merchantText,
                                        String supportText, String engineerText,
                                        Instant generatedAt) {
        Explanation explanation = new Explanation();
        explanation.paymentId = paymentId;
        explanation.level = "L4";
        explanation.path = determinable ? "MODEL" : "ABSTAIN";
        explanation.factSetHash = factSetHash;
        explanation.promptVersion = promptVersion;
        explanation.rootCause = determinable ? rootCause : null;
        explanation.determinable = determinable;
        explanation.confidence = determinable && confidence != null
                ? BigDecimal.valueOf(confidence).setScale(3, java.math.RoundingMode.HALF_UP)
                : null;
        explanation.hypothesis = determinable;
        explanation.abstained = !determinable;
        explanation.claims = claims;
        explanation.citations = citations;
        explanation.merchantText = merchantText;
        explanation.supportText = supportText;
        explanation.engineerText = engineerText;
        explanation.generatedAt = generatedAt;
        return explanation;
    }

    public Long getId() {
        return id;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getLevel() {
        return level;
    }

    public String getPath() {
        return path;
    }

    public String getFactSetHash() {
        return factSetHash;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getRootCause() {
        return rootCause;
    }

    public boolean isDeterminable() {
        return determinable;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public boolean isHypothesis() {
        return hypothesis;
    }

    public boolean isAbstained() {
        return abstained;
    }

    public String getClaims() {
        return claims;
    }

    public String getCitations() {
        return citations;
    }

    public String getMerchantText() {
        return merchantText;
    }

    public String getSupportText() {
        return supportText;
    }

    public String getEngineerText() {
        return engineerText;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }
}
