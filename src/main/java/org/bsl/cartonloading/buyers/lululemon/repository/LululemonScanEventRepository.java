package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.LululemonScanEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface LululemonScanEventRepository extends MongoRepository<LululemonScanEvent, String> {
    List<LululemonScanEvent> findTop100ByOrderIdAndBuyerCodeOrderByCreatedAtDesc(String orderId, String buyerCode);
    List<LululemonScanEvent> findTop200ByPoIdOrderByCreatedAtDesc(String poId);
    List<LululemonScanEvent> findTop200ByCartonIdOrderByCreatedAtDesc(String cartonId);
    void deleteByOrderIdAndBuyerCode(String orderId, String buyerCode);
    void deleteByPoId(String poId);
}
