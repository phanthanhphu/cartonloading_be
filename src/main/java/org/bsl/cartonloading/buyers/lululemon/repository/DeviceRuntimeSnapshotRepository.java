package org.bsl.cartonloading.buyers.lululemon.repository;

import org.bsl.cartonloading.buyers.lululemon.model.DeviceRuntimeSnapshot;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DeviceRuntimeSnapshotRepository extends MongoRepository<DeviceRuntimeSnapshot, String> { }
