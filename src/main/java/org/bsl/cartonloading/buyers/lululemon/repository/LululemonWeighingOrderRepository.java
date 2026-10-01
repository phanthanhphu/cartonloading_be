package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.LululemonWeighingOrder;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface LululemonWeighingOrderRepository extends MongoRepository<LululemonWeighingOrder, String> {
    List<LululemonWeighingOrder> findByOrderIdAndBuyerCodeOrderByCreatedAtDesc(String orderId, String buyerCode);
    Optional<LululemonWeighingOrder> findByIdAndOrderIdAndBuyerCode(String id, String orderId, String buyerCode);
    void deleteByOrderIdAndBuyerCode(String orderId, String buyerCode);
}
