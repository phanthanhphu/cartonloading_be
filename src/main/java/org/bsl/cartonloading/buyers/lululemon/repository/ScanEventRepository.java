package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.ScanEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ScanEventRepository extends MongoRepository<ScanEvent, String> {
    List<ScanEvent> findTop100ByOrderIdAndBuyerCodeOrderByCreatedAtDesc(String orderId, String buyerCode);
    List<ScanEvent> findTop200ByPoIdOrderByCreatedAtDesc(String poId);
    List<ScanEvent> findTop200ByCartonIdOrderByCreatedAtDesc(String cartonId);
    void deleteByOrderIdAndBuyerCode(String orderId, String buyerCode);
    void deleteByPoId(String poId);
}
