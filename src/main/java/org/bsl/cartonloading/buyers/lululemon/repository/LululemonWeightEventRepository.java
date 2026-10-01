package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.LululemonWeightEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface LululemonWeightEventRepository extends MongoRepository<LululemonWeightEvent, String> {
    List<LululemonWeightEvent> findByWeighingOrderIdOrderByCreatedAtDesc(String weighingOrderId);
    List<LululemonWeightEvent> findTop200ByCartonIdOrderByCreatedAtDesc(String cartonId);
    void deleteByOrderIdAndBuyerCode(String orderId, String buyerCode);
}
