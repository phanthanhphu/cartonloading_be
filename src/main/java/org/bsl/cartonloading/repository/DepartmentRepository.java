package org.bsl.cartonloading.repository;

import org.bsl.cartonloading.model.Department;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface DepartmentRepository extends MongoRepository<Department, String> {
    Optional<Department> findByFactoryIgnoreCaseAndDivisionIgnoreCaseAndDepartmentNameIgnoreCase(
            String factory, String division, String departmentName);
}
