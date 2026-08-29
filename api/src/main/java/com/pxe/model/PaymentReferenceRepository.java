package com.pxe.model;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentReferenceRepository extends JpaRepository<PaymentReference, Long> {

    long countByPaymentId(String paymentId);

    List<PaymentReference> findByPaymentIdOrderByHopSeqAsc(String paymentId);
}
