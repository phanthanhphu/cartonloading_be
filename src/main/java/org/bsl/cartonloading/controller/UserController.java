package org.bsl.cartonloading.controller;

import jakarta.validation.Valid;
import org.bsl.cartonloading.dto.UserDTO;
import org.bsl.cartonloading.dto.admin.PasswordResetRequest;
import org.bsl.cartonloading.dto.admin.UserAdminRequest;
import org.bsl.cartonloading.service.UserAdminService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("@accessControl.isAdmin()")
public class UserController {
    private final UserAdminService service;
    public UserController(UserAdminService service) { this.service = service; }

    @GetMapping
    public Page<UserDTO> list(@RequestParam(required = false) String username,
                              @RequestParam(required = false) String email,
                              @RequestParam(required = false) String phone,
                              @RequestParam(required = false) String address,
                              @RequestParam(required = false) String role,
                              @RequestParam(required = false) String departmentId,
                              @RequestParam(required = false) Boolean enabled,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "25") int size) {
        return service.list(username, email, phone, address, role, departmentId, enabled, page, size);
    }

    @PostMapping public UserDTO create(@Valid @RequestBody UserAdminRequest request) { return service.create(request); }
    @PutMapping("/{id}") public UserDTO update(@PathVariable String id, @Valid @RequestBody UserAdminRequest request) { return service.update(id, request); }
    @PutMapping("/{id}/password") public ResponseEntity<Map<String, Object>> resetPassword(@PathVariable String id, @Valid @RequestBody PasswordResetRequest request) {
        service.resetPassword(id, request.password());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{id}/password/generate")
    public ResponseEntity<Map<String, String>> generatePassword(@PathVariable String id) {
        String password = service.generateAndResetPassword(id);
        return ResponseEntity.ok(Map.of("password", password));
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable String id) { service.delete(id); return ResponseEntity.noContent().build(); }
}
