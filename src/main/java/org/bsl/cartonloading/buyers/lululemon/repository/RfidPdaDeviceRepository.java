package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.RfidPdaDevice;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RfidPdaDeviceRepository extends MongoRepository<RfidPdaDevice, String> { }
