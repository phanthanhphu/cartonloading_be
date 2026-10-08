package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.CartonCheckingRecord;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;
import java.util.Optional;

public interface CartonCheckingRepository extends MongoRepository<CartonCheckingRecord, String> {
    Optional<CartonCheckingRecord> findFirstByCartonIdAndOrderIdAndBuyerCodeOrderByCheckedAtDesc(
            String cartonId, String orderId, String buyerCode);
    List<CartonCheckingRecord> findTop50ByCartonIdAndOrderIdAndBuyerCodeOrderByCheckedAtDesc(
            String cartonId, String orderId, String buyerCode);
}
