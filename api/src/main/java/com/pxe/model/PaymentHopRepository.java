package com.pxe.model;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentHopRepository extends JpaRepository<PaymentHop, PaymentHop.Key> {

    long countByPaymentId(String paymentId);

    List<PaymentHop> findByPaymentIdOrderBySeqAsc(String paymentId);
}
