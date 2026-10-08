package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface CartonRepository extends MongoRepository<Carton, String> {
    List<Carton> findByPoIdOrderByCartonNoAsc(String poId);
    List<Carton> findByPoIdIn(java.util.Collection<String> poIds);
    Page<Carton> findByPoIdAndOrderIdAndBuyerCode(String poId, String orderId, String buyerCode, Pageable pageable);
    boolean existsByPoId(String poId);
    long countByPoId(String poId);
    Optional<Carton> findByIdAndPoId(String id, String poId);
    Optional<Carton> findBySscc18(String sscc18);
    Optional<Carton> findByIdAndBuyerCode(String id, String buyerCode);
    List<Carton> findByBuyerCodeAndLabelConfirmedSkuIgnoreCase(String buyerCode, String sku);
    List<Carton> findByOrderIdAndBuyerCodeAndLabelConfirmedSkuIgnoreCase(String orderId, String buyerCode, String sku);
    void deleteByPoId(String poId);

    Optional<Carton> findByIdAndOrderIdAndBuyerCode(String id, String orderId, String buyerCode);
    List<Carton> findByOrderIdAndBuyerCodeOrderByPoNumberAscCartonNoAsc(String orderId, String buyerCode);
    boolean existsBySscc18(String sscc18);
    void deleteByOrderIdAndBuyerCode(String orderId, String buyerCode);
}
