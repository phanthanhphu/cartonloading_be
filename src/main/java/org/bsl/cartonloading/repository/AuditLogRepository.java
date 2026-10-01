package org.bsl.cartonloading.repository;

import org.bsl.cartonloading.model.AuditLog;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AuditLogRepository extends MongoRepository<AuditLog, String> {

    List<AuditLog> findByUsername(String username);

    List<AuditLog> findByResourceType(String resourceType);

    Page<AuditLog> findByUsernameContainingIgnoreCase(String username, Pageable pageable);

}