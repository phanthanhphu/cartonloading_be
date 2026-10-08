package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.WeighingOrder;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface WeighingOrderRepository extends MongoRepository<WeighingOrder, String> {
    List<WeighingOrder> findByOrderIdAndBuyerCodeOrderByCreatedAtDesc(String orderId, String buyerCode);
    List<WeighingOrder> findByBuyerCodeOrderByCreatedAtDesc(String buyerCode);
    Optional<WeighingOrder> findByIdAndOrderIdAndBuyerCode(String id, String orderId, String buyerCode);
    void deleteByOrderIdAndBuyerCode(String orderId, String buyerCode);
}
