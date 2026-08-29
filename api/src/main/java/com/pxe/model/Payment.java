package com.pxe.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A payment, with the outcome resolved from its hops.
 *
 * <p>{@code tag} and {@code responseCode} are derived, never loaded: {@code expected.tag} in
 * payment-scenarios.json is golden data and the pipeline must reach the same answer on its own.
 *
 * <p>Explanation is a debt. A payment that succeeded cleanly owes nothing; anything else owes an
 * explanation and the debt stays open until one exists. The queue is a query on this table, sorted
 * by exposure, and it is the number on the console that should trend to zero.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Instrument instrument;

    @Column(nullable = false)
    private String rail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    private OutcomeTag tag;

    @Column(name = "response_code")
    private String responseCode;

    @Column(name = "terminal_at")
    private Instant terminalAt;

    @Column(name = "debt_open", nullable = false)
    private boolean debtOpen;

    @Column(name = "debt_opened_at")
    private Instant debtOpenedAt;

    @Column(name = "debt_closed_at")
    private Instant debtClosedAt;

    protected Payment() {
    }

    public Payment(String id, String merchantId, long amountMinor, String currency,
                   Instrument instrument, String rail, Instant createdAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.instrument = instrument;
        this.rail = rail;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instrument getInstrument() {
        return instrument;
    }

    public String getRail() {
        return rail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public OutcomeTag getTag() {
        return tag;
    }

    public String getResponseCode() {
        return responseCode;
    }

    public Instant getTerminalAt() {
        return terminalAt;
    }

    public boolean isDebtOpen() {
        return debtOpen;
    }

    public Instant getDebtOpenedAt() {
        return debtOpenedAt;
    }

    public Instant getDebtClosedAt() {
        return debtClosedAt;
    }

    public void resolveOutcome(OutcomeTag tag, String responseCode, Instant terminalAt) {
        this.tag = tag;
        this.responseCode = responseCode;
        this.terminalAt = terminalAt;
    }

    /** A failure, or a success that did not reconcile, incurs the debt. */
    public void openDebt(Instant at) {
        this.debtOpen = true;
        this.debtOpenedAt = at;
        this.debtClosedAt = null;
    }

    /** An explanation exists. The debt is paid. */
    public void closeDebt(Instant at) {
        this.debtOpen = false;
        this.debtClosedAt = at;
    }

    /** Nothing was ever owed. Not the same as a debt that was opened and then paid. */
    public void oweNothing() {
        this.debtOpen = false;
        this.debtOpenedAt = null;
        this.debtClosedAt = null;
    }
}
