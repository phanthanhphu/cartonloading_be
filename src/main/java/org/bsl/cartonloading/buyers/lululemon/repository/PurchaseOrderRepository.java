package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface PurchaseOrderRepository extends MongoRepository<PurchaseOrder, String> {
    List<PurchaseOrder> findByOrderIdAndBuyerCodeOrderByPoNumberAsc(String orderId, String buyerCode);
    Page<PurchaseOrder> findByOrderIdAndBuyerCode(String orderId, String buyerCode, Pageable pageable);
    List<PurchaseOrder> findAllByOrderIdAndBuyerCodeAndPoNumber(String orderId, String buyerCode, String poNumber);
    List<PurchaseOrder> findByBuyerCodeAndPoNumber(String buyerCode, String poNumber);
    List<PurchaseOrder> findByBuyerCodeAndSkuIgnoreCase(String buyerCode, String sku);
    List<PurchaseOrder> findByOrderIdAndBuyerCodeAndSku(String orderId, String buyerCode, String sku);
    List<PurchaseOrder> findByOrderIdAndBuyerCodeAndSkuIgnoreCase(String orderId, String buyerCode, String sku);
    /** SKU ownership is buyer-wide: one SKU may belong to only one LULULEMON logical PO record. */
    List<PurchaseOrder> findByBuyerCodeAndSku(String buyerCode, String sku);
    Optional<PurchaseOrder> findByIdAndOrderIdAndBuyerCode(String id, String orderId, String buyerCode);
    void deleteByOrderIdAndBuyerCode(String orderId, String buyerCode);
}
