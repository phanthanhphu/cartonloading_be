package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.PrintRequest;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface PrintRequestRepository extends MongoRepository<PrintRequest, String> {
    Optional<PrintRequest> findByIdAndBuyerCode(String id, String buyerCode);
}
