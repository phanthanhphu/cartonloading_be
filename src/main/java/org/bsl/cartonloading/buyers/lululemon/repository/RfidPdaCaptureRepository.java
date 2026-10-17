package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.RfidPdaCapture;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RfidPdaCaptureRepository extends MongoRepository<RfidPdaCapture, String> {
    boolean existsBySessionId(String sessionId);
}
