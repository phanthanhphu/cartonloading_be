package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.WeightProfile;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface WeightProfileRepository extends MongoRepository<WeightProfile, String> {
    Optional<WeightProfile> findByOrderIdAndPoId(String orderId, String poId);
    List<WeightProfile> findByOrderIdAndBuyerCode(String orderId, String buyerCode);
    void deleteByOrderIdAndBuyerCode(String orderId, String buyerCode);
}
