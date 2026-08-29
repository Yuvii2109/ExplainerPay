package com.pxe.explain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelCallRepository extends JpaRepository<ModelCall, Long> {

    List<ModelCall> findByPaymentId(String paymentId);
}
