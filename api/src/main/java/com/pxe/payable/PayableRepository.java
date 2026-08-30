package com.pxe.payable;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayableRepository extends JpaRepository<Payable, String> {

    /** The queue an ops team works: still owed, oldest due date first. */
    List<Payable> findBySettledAtIsNullOrderByDueOnAsc();

    List<Payable> findByMerchantIdAndSettledAtIsNullOrderByDueOnAsc(String merchantId);
}
