package com.pxe.explain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One call to pxe-ai, admitted or refused.
 *
 * <p>The row exists whether or not the call happened: an admission decision that says no is the
 * interesting half of the funnel, and a counter that only counts spending cannot show it. Read
 * surface only at phase 3; the phase that spends tokens writes it.
 */
@Entity
@Table(name = "model_calls")
public class ModelCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private String paymentId;

    /** SYNTHESIS for job A, HYPOTHESIS for job B. */
    @Column(nullable = false)
    private String job;

    @Column(nullable = false)
    private boolean admitted;

    private Integer priority;

    private String reason;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "rejected_by")
    private String rejectedBy;

    @Column(name = "rejected_payload")
    private String rejectedPayload;

    protected ModelCall() {
    }

    /** A call that was refused. The interesting half of the funnel, recorded rather than implied. */
    public static ModelCall refused(String paymentId, String job, String reason) {
        ModelCall call = new ModelCall();
        call.paymentId = paymentId;
        call.job = job;
        call.admitted = false;
        call.reason = reason;
        return call;
    }

    /** A call that was made, with what it cost. */
    public static ModelCall spent(String paymentId, String job, long priority, String reason,
                                  int promptTokens, int completionTokens, long latencyMs) {
        ModelCall call = new ModelCall();
        call.paymentId = paymentId;
        call.job = job;
        call.admitted = true;
        call.priority = (int) priority;
        call.reason = reason;
        call.promptTokens = promptTokens;
        call.completionTokens = completionTokens;
        call.latencyMs = latencyMs;
        return call;
    }

    /** A call that was made and whose response was thrown away. Section 15 names the rule. */
    public static ModelCall rejected(String paymentId, String job, String rejectedBy,
                                     int promptTokens, int completionTokens, long latencyMs) {
        ModelCall call = spent(paymentId, job, 0, "response rejected", promptTokens,
                completionTokens, latencyMs);
        call.rejectedBy = rejectedBy;
        return call;
    }

    /**
     * A payload the validator refused, where no model was ever called.
     *
     * <p>Not admitted, and it must not be: the probe feeds a canned response straight to the
     * contract, so counting it as a model call would make a payment look like it had spent a token
     * and quietly drop deterministic coverage.
     */
    public static ModelCall probeRejection(String paymentId, String rejectedBy, String reason,
                                           String payload) {
        ModelCall call = new ModelCall();
        call.paymentId = paymentId;
        call.job = "PROBE";
        call.admitted = false;
        call.reason = reason;
        call.rejectedBy = rejectedBy;
        call.rejectedPayload = payload;
        return call;
    }

    /** A rejection with the payload that caused it, so beat 10 can put it on screen. */
    public static ModelCall rejectedWithPayload(String paymentId, String job, String rejectedBy,
                                                String reason, String payload) {
        ModelCall call = new ModelCall();
        call.paymentId = paymentId;
        call.job = job;
        call.admitted = true;
        call.reason = reason;
        call.rejectedBy = rejectedBy;
        call.rejectedPayload = payload;
        return call;
    }

    public Long getId() {
        return id;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getJob() {
        return job;
    }

    public boolean isAdmitted() {
        return admitted;
    }

    public int tokens() {
        return (promptTokens == null ? 0 : promptTokens)
                + (completionTokens == null ? 0 : completionTokens);
    }

    public String getRejectedBy() {
        return rejectedBy;
    }

    public String getReason() {
        return reason;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public String getRejectedPayload() {
        return rejectedPayload;
    }
}
