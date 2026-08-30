package com.pxe.payable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Money the platform still owes a merchant. Section 8.1.
 *
 * <p>This is not the explanation debt. A payable is discharged by money and a debt is discharged by
 * an answer, and the useful thing about keeping both is watching a single payment close one while
 * opening the other.
 *
 * <p>{@code remaining_minor} moves by what the merchant was <em>credited</em>, never by what the
 * customer was charged. A payment the rails tagged as succeeded can still leave this row open, and
 * when it does, the amount left is the size of the problem.
 */
@Entity
@Table(name = "merchant_payables")
public class Payable {

    @Id
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private String description;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Column(nullable = false)
    private String currency;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "remaining_minor", nullable = false)
    private long remainingMinor;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "last_payment_id")
    private String lastPaymentId;

    protected Payable() {
    }

    public Payable(String id, String merchantId, String description, LocalDate dueOn,
                   String currency, long amountMinor) {
        this.id = id;
        this.merchantId = merchantId;
        this.description = description;
        this.dueOn = dueOn;
        this.currency = currency;
        this.amountMinor = amountMinor;
        this.remainingMinor = amountMinor;
    }

    /**
     * Credit what actually reached the merchant.
     *
     * <p>Overpaying settles the row rather than turning into a balance, because this system does
     * not hold one. Crediting nothing is a legal outcome and the common one when a payment failed:
     * the row is untouched and still shows what it always did.
     */
    public void credit(long creditedMinor, String paymentId, Instant at) {
        if (creditedMinor <= 0 || settledAt != null) {
            return;
        }
        remainingMinor = Math.max(0, remainingMinor - creditedMinor);
        lastPaymentId = paymentId;
        if (remainingMinor == 0) {
            settledAt = at;
        }
    }

    public String getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getDueOn() {
        return dueOn;
    }

    public String getCurrency() {
        return currency;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public long getRemainingMinor() {
        return remainingMinor;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public String getLastPaymentId() {
        return lastPaymentId;
    }
}
