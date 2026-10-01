package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.LululemonPo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface LululemonPoRepository extends MongoRepository<LululemonPo, String> {
    List<LululemonPo> findByOrderIdAndBuyerCodeOrderByPoNumberAsc(String orderId, String buyerCode);
    Page<LululemonPo> findByOrderIdAndBuyerCode(String orderId, String buyerCode, Pageable pageable);
    Optional<LululemonPo> findByOrderIdAndBuyerCodeAndPoNumber(String orderId, String buyerCode, String poNumber);
    List<LululemonPo> findByOrderIdAndBuyerCodeAndSku(String orderId, String buyerCode, String sku);
    Optional<LululemonPo> findByIdAndOrderIdAndBuyerCode(String id, String orderId, String buyerCode);
    void deleteByOrderIdAndBuyerCode(String orderId, String buyerCode);
}
