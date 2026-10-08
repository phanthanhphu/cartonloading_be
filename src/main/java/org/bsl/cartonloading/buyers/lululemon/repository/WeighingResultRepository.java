package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.WeighingResult;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface WeighingResultRepository extends MongoRepository<WeighingResult, String> {
    List<WeighingResult> findByWeighingOrderIdOrderByCreatedAtDesc(String weighingOrderId);
    List<WeighingResult> findByShippingScheduleIdOrderByCreatedAtDesc(String shippingScheduleId);
    List<WeighingResult> findByBuyerCodeOrderByCreatedAtDesc(String buyerCode);
    List<WeighingResult> findTop200ByCartonIdOrderByCreatedAtDesc(String cartonId);
    void deleteByOrderIdAndBuyerCode(String orderId, String buyerCode);
}
