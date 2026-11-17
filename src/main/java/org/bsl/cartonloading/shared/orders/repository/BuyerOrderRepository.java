package org.bsl.cartonloading.shared.orders.repository;

import org.bsl.cartonloading.shared.orders.model.OrderDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface BuyerOrderRepository extends MongoRepository<OrderDocument, String> {
    List<OrderDocument> findByBuyerCode(String buyerCode);
    List<OrderDocument> findByBuyerCodeAndFactoryCode(String buyerCode, String factoryCode);
    Optional<OrderDocument> findByIdAndBuyerCode(String id, String buyerCode);
}
