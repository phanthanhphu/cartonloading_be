package org.bsl.cartonloading.repository;

import org.bsl.cartonloading.model.Factory;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;
import java.util.Optional;

public interface FactoryRepository extends MongoRepository<Factory, String> {
    Optional<Factory> findByBuyerKeyAndCode(String buyerKey, String code);
    List<Factory> findByBuyerKey(String buyerKey);
}
