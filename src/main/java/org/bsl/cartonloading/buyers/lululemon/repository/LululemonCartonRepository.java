package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.LululemonCarton;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface LululemonCartonRepository extends MongoRepository<LululemonCarton, String> {
    List<LululemonCarton> findByPoIdOrderByCartonNoAsc(String poId);
    Page<LululemonCarton> findByPoIdAndOrderIdAndBuyerCode(String poId, String orderId, String buyerCode, Pageable pageable);
    boolean existsByPoId(String poId);
    long countByPoId(String poId);
    Optional<LululemonCarton> findByIdAndPoId(String id, String poId);
    Optional<LululemonCarton> findBySscc18(String sscc18);
    void deleteByPoId(String poId);

    Optional<LululemonCarton> findByIdAndOrderIdAndBuyerCode(String id, String orderId, String buyerCode);
    List<LululemonCarton> findByOrderIdAndBuyerCodeOrderByPoNumberAscCartonNoAsc(String orderId, String buyerCode);
    boolean existsBySscc18(String sscc18);
    void deleteByOrderIdAndBuyerCode(String orderId, String buyerCode);
}
