package com.pxe.model;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    /** The debt queue: WHERE debt_open = true ORDER BY amount_minor DESC. No separate table. */
    List<Payment> findByDebtOpenTrueOrderByAmountMinorDesc();
}
