package com.pxe.deviation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviationRepository extends JpaRepository<Deviation, Long> {

    List<Deviation> findByPaymentIdOrderByTypeAsc(String paymentId);
}
