package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.CartonItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface CartonItemRepository extends MongoRepository<CartonItem, String> {
    List<CartonItem> findByCartonIdOrderByItemNoAsc(String cartonId);
    Page<CartonItem> findByCartonIdAndOrderIdAndBuyerCode(String cartonId, String orderId, String buyerCode, Pageable pageable);
    boolean existsByCartonId(String cartonId);
    long countByCartonId(String cartonId);
    Optional<CartonItem> findFirstByCartonIdAndStatusOrderByItemNoAsc(String cartonId, String status);
    long countByCartonIdAndStatus(String cartonId, String status);
    long countByPoIdAndStatus(String poId, String status);
    void deleteByPoId(String poId);
    List<CartonItem> findByPoIdOrderByCartonNoAscItemNoAsc(String poId);

    Optional<CartonItem> findFirstByCartonIdAndStatusOrderByItemNoDesc(String cartonId, String status);
    void deleteByOrderIdAndBuyerCode(String orderId, String buyerCode);
}
