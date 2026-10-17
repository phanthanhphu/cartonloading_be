package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.RfidScanSession;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import java.util.Optional;
import java.util.List;

public interface RfidScanSessionRepository extends MongoRepository<RfidScanSession, String> {
    Optional<RfidScanSession> findTopByCartonIdAndBuyerCodeOrderByStartedAtDesc(String cartonId, String buyerCode);
    List<RfidScanSession> findByCartonIdAndBuyerCodeOrderByStartedAtDesc(String cartonId, String buyerCode);
    @Query(value = "{'buyerCode': ?0, 'epcs': ?1, 'status': 'PASS', 'cartonId': {'$ne': ?2}}", exists = true)
    boolean existsVerifiedEpcInOtherCarton(String buyerCode, String epc, String cartonId);
}
