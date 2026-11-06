package org.bsl.cartonloading.service;

import org.bsl.cartonloading.dto.admin.DepartmentRequest;
import org.bsl.cartonloading.model.Department;
import org.bsl.cartonloading.model.Factory;
import org.bsl.cartonloading.repository.DepartmentRepository;
import org.bsl.cartonloading.repository.UserRepository;
import org.bsl.cartonloading.common.socket.AppSocketPublisher;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DepartmentFactoryAssignmentSafetyTest {
    @Test void cannotMoveAssignedDepartmentToAnotherFactory() {
        DepartmentRepository departments = mock(DepartmentRepository.class);
        UserRepository users = mock(UserRepository.class);
        FactoryCatalogService factories = mock(FactoryCatalogService.class);
        DepartmentAdminService subject = new DepartmentAdminService(departments, users,
                mock(AuditLogService.class), factories, mock(AppSocketPublisher.class));
        Department department = new Department();
        department.setId("d1");
        department.setFactory("F1");
        department.setDivision("Division");
        department.setDepartmentName("Original");
        when(departments.findById("d1")).thenReturn(Optional.of(department));
        when(users.existsByDepartmentId("d1")).thenReturn(true);
        when(departments.findByFactoryIgnoreCaseAndDivisionIgnoreCaseAndDepartmentNameIgnoreCase("F2", "Division", "Original"))
                .thenReturn(Optional.empty());
        when(factories.requireActive("LULULEMON", "F2")).thenReturn(new Factory());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> subject.update("d1", new DepartmentRequest("F2", "Division", "Original")));
        assertTrue(error.getMessage().contains("Reassign those Users"));
        verify(departments, never()).save(any(Department.class));
    }
}
