package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.model.AuditLog;
import org.bsl.cartonloading.service.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit-logs")
@PreAuthorize("@accessControl.isAdmin()")
public class AuditLogController {
    private final AuditLogService service;
    public AuditLogController(AuditLogService service) { this.service = service; }

    @GetMapping
    public Page<AuditLog> list(@RequestParam(required = false) String username,
                               @RequestParam(required = false) String action,
                               @RequestParam(required = false) String resourceType,
                               @RequestParam(required = false) String resourceId,
                               @RequestParam(required = false) String ipAddress,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "25") int size) {
        return service.search(username, action, resourceType, resourceId, ipAddress, page, size);
    }
}
