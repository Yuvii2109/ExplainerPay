package com.pxe.explain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExplanationRepository extends JpaRepository<Explanation, Long> {

    Optional<Explanation> findByPaymentId(String paymentId);

    List<Explanation> findAllByOrderByPaymentIdAsc();
}
