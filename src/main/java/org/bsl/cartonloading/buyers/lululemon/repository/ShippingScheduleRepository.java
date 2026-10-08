package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.ShippingSchedule;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ShippingScheduleRepository extends MongoRepository<ShippingSchedule, String> {
    List<ShippingSchedule> findByOrderIdAndBuyerCodeOrderByExFtyDateAscCreatedAtDesc(String orderId, String buyerCode);
    Optional<ShippingSchedule> findByIdAndOrderIdAndBuyerCode(String id, String orderId, String buyerCode);
    void deleteByOrderIdAndBuyerCode(String orderId, String buyerCode);
}
