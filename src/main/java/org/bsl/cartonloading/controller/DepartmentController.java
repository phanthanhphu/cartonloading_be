package org.bsl.cartonloading.controller;

import jakarta.validation.Valid;
import org.bsl.cartonloading.dto.admin.DepartmentRequest;
import org.bsl.cartonloading.model.Department;
import org.bsl.cartonloading.service.DepartmentAdminService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/departments")
@PreAuthorize("@accessControl.isAdmin()")
public class DepartmentController {
    private final DepartmentAdminService service;
    public DepartmentController(DepartmentAdminService service) { this.service = service; }

    @GetMapping public Page<Department> list(@RequestParam(required = false) String factory,
            @RequestParam(required = false) String division,
            @RequestParam(required = false) String departmentName,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.list(factory, division, departmentName, page, size);
    }
    @PostMapping public Department create(@Valid @RequestBody DepartmentRequest request) { return service.create(request); }
    @PutMapping("/{id}") public Department update(@PathVariable String id, @Valid @RequestBody DepartmentRequest request) { return service.update(id, request); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable String id) { service.delete(id); return ResponseEntity.noContent().build(); }
}
