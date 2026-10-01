package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.LululemonWeightProfile;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface LululemonWeightProfileRepository extends MongoRepository<LululemonWeightProfile, String> {
    Optional<LululemonWeightProfile> findByOrderIdAndPoId(String orderId, String poId);
    List<LululemonWeightProfile> findByOrderIdAndBuyerCode(String orderId, String buyerCode);
    void deleteByOrderIdAndBuyerCode(String orderId, String buyerCode);
}
