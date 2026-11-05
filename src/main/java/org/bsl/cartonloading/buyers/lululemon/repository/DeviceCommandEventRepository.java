package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.DeviceCommandEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DeviceCommandEventRepository extends MongoRepository<DeviceCommandEvent, String> { }
