package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.PackingImportBatch;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface PackingImportBatchRepository extends MongoRepository<PackingImportBatch, String> {
    List<PackingImportBatch> findByBuyerCodeAndFactoryCodeOrderByImportedAtDesc(String buyerCode, String factoryCode);
}
