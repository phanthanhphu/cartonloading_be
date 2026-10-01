package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.LululemonCartonItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface LululemonCartonItemRepository extends MongoRepository<LululemonCartonItem, String> {
    List<LululemonCartonItem> findByCartonIdOrderByItemNoAsc(String cartonId);
    Page<LululemonCartonItem> findByCartonIdAndOrderIdAndBuyerCode(String cartonId, String orderId, String buyerCode, Pageable pageable);
    boolean existsByCartonId(String cartonId);
    long countByCartonId(String cartonId);
    Optional<LululemonCartonItem> findFirstByCartonIdAndStatusOrderByItemNoAsc(String cartonId, String status);
    long countByCartonIdAndStatus(String cartonId, String status);
    long countByPoIdAndStatus(String poId, String status);
    void deleteByPoId(String poId);
    List<LululemonCartonItem> findByPoIdOrderByCartonNoAscItemNoAsc(String poId);

    Optional<LululemonCartonItem> findFirstByCartonIdAndStatusOrderByItemNoDesc(String cartonId, String status);
    void deleteByOrderIdAndBuyerCode(String orderId, String buyerCode);
}
