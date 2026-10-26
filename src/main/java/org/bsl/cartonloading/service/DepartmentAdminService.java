package org.bsl.cartonloading.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.factory.FactoryCodes;
import org.bsl.cartonloading.common.constants.AppMessages;
import org.bsl.cartonloading.dto.admin.DepartmentRequest;
import org.bsl.cartonloading.model.Department;
import org.bsl.cartonloading.repository.DepartmentRepository;
import org.bsl.cartonloading.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.bsl.cartonloading.common.socket.AppSocketPublisher;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class DepartmentAdminService {
    private final DepartmentRepository repository;
    private final UserRepository userRepository;
    private final AuditLogService audit;
    private final FactoryCatalogService factories;
    private final AppSocketPublisher socketPublisher;

    public DepartmentAdminService(DepartmentRepository repository, UserRepository userRepository, AuditLogService audit, FactoryCatalogService factories, AppSocketPublisher socketPublisher) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.audit = audit;
        this.factories = factories;
        this.socketPublisher = socketPublisher;
    }

    public Page<Department> list(String factory, String division, String departmentName, int page, int size) {
        String factoryKey = clean(factory);
        String divisionKey = clean(division);
        String nameKey = clean(departmentName);
        List<Department> rows = repository.findAll().stream()
                .filter(d -> factoryKey == null || contains(d.getFactory(), factoryKey))
                .filter(d -> divisionKey == null || contains(d.getDivision(), divisionKey))
                .filter(d -> nameKey == null || contains(d.getDepartmentName(), nameKey))
                .sorted(Comparator.comparing(Department::getFactory, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(Department::getDivision, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(Department::getDepartmentName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        Pageable pageable = PageRequest.of(Math.max(0, page), clamp(size));
        int from = Math.min((int) pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
    }

    public Department create(DepartmentRequest request) {
        String factory = factory(request.factory());
        String division = required(request.division(), AppMessages.DIVISION_REQUIRED);
        String name = required(request.departmentName(), AppMessages.DEPARTMENT_NAME_REQUIRED);
        if (repository.findByFactoryIgnoreCaseAndDivisionIgnoreCaseAndDepartmentNameIgnoreCase(factory, division, name).isPresent()) {
            throw new IllegalArgumentException(AppMessages.DEPARTMENT_ALREADY_EXISTS);
        }
        Department row = new Department();
        row.setFactory(factory);
        row.setDivision(division);
        row.setDepartmentName(name);
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(row.getCreatedAt());
        Department saved = repository.save(row);
        audit.log(RequestActor.current(), AppConstants.AuditAction.CREATE, AppConstants.AuditResource.DEPARTMENT, saved.getId(), label(saved), null, null);
        socketPublisher.organizationChanged(AppConstants.OrganizationRealtime.DEPARTMENT, AppConstants.AuditAction.CREATE);
        return saved;
    }

    public Department update(String id, DepartmentRequest request) {
        Department row = repository.findById(id).orElseThrow(() -> new IllegalArgumentException(AppMessages.DEPARTMENT_NOT_FOUND));
        String factory = factory(request.factory());
        String division = required(request.division(), AppMessages.DIVISION_REQUIRED);
        String name = required(request.departmentName(), AppMessages.DEPARTMENT_NAME_REQUIRED);
        repository.findByFactoryIgnoreCaseAndDivisionIgnoreCaseAndDepartmentNameIgnoreCase(factory, division, name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> { throw new IllegalArgumentException(AppMessages.DEPARTMENT_ALREADY_EXISTS); });
        row.setFactory(factory);
        row.setDivision(division);
        row.setDepartmentName(name);
        row.setUpdatedAt(LocalDateTime.now());
        Department saved = repository.save(row);
        audit.log(RequestActor.current(), AppConstants.AuditAction.UPDATE, AppConstants.AuditResource.DEPARTMENT, saved.getId(), label(saved), null, null);
        socketPublisher.organizationChanged(AppConstants.OrganizationRealtime.DEPARTMENT, AppConstants.AuditAction.UPDATE);
        return saved;
    }

    public void delete(String id) {
        if (!repository.existsById(id)) throw new IllegalArgumentException(AppMessages.DEPARTMENT_NOT_FOUND);
        if (userRepository.existsByDepartmentId(id)) {
            throw new IllegalArgumentException(AppMessages.DEPARTMENT_ASSIGNED_TO_USERS);
        }
        Department row = repository.findById(id).orElseThrow(() -> new IllegalArgumentException(AppMessages.DEPARTMENT_NOT_FOUND));
        repository.deleteById(id);
        audit.log(RequestActor.current(), AppConstants.AuditAction.DELETE, AppConstants.AuditResource.DEPARTMENT, id, label(row), null, null);
        socketPublisher.organizationChanged(AppConstants.OrganizationRealtime.DEPARTMENT, AppConstants.AuditAction.DELETE);
    }

    private String label(Department row) {
        return AppMessages.departmentAuditLabel(row.getFactory(), row.getDivision(), row.getDepartmentName());
    }

    private String factory(String value) {
        String v = FactoryCodes.requireValid(required(value, AppMessages.FACTORY_REQUIRED));
        factories.requireActive(AppConstants.Buyer.LULULEMON, v);
        return v;
    }

    private int clamp(int size) { return Math.max(1, Math.min(size, 200)); }
    private String required(String value, String message) { String v = clean(value); if (v == null) throw new IllegalArgumentException(message); return v; }
    private String clean(String value) { if (value == null) return null; String v = value.trim().replaceAll("\\s+", " "); return v.isEmpty() ? null : v; }
    private boolean contains(String value, String q) { return value != null && value.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)); }
}
