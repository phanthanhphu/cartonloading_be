package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.LululemonPrintRequest;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface LululemonPrintRequestRepository extends MongoRepository<LululemonPrintRequest, String> {
    Optional<LululemonPrintRequest> findByIdAndBuyerCode(String id, String buyerCode);
}
