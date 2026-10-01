package org.bsl.cartonloading.buyers.es.repository;

import org.bsl.cartonloading.buyers.es.model.FactoryBarcode;
import org.bsl.cartonloading.buyers.es.enums.FactoryBarcodeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface FactoryBarcodeRepository extends MongoRepository<FactoryBarcode, String> {
    long countByStatus(FactoryBarcodeStatus status);
    Page<FactoryBarcode> findByStatusOrderByBarcodeAsc(FactoryBarcodeStatus status, Pageable pageable);

    Optional<FactoryBarcode> findByBarcode(String barcode);
    List<FactoryBarcode> findByBatchIdOrderByRunningNumberAsc(String batchId);
}
