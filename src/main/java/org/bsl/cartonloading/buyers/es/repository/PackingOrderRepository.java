package org.bsl.cartonloading.buyers.es.repository;

import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface PackingOrderRepository extends MongoRepository<PackingOrder, String> {
    List<PackingOrder> findByBuyerCode(String buyerCode);
    Optional<PackingOrder> findByIdAndBuyerCode(String id, String buyerCode);
}
