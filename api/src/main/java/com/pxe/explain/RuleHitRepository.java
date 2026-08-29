package com.pxe.explain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleHitRepository extends JpaRepository<RuleHit, Long> {

    List<RuleHit> findByPaymentId(String paymentId);
}
