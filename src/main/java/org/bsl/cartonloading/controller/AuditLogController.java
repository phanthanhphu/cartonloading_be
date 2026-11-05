package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.common.constants.AppConstants;

import org.bsl.cartonloading.model.AuditLog;
import org.bsl.cartonloading.service.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit-logs")
@PreAuthorize("@accessControl.canViewAuditLogs()")
public class AuditLogController {
    private final AuditLogService service;
    public AuditLogController(AuditLogService service) { this.service = service; }

    @GetMapping
    public Page<AuditLog> list(@RequestParam(required = false) String username,
                               @RequestParam(required = false) String action,
                               @RequestParam(required = false) String resourceType,
                               @RequestParam(required = false) String resourceId,
                               @RequestParam(required = false) String ipAddress,
                               @RequestParam(required = false) String buyerCode,
                               @RequestParam(required = false) String factoryCode,
                               @RequestParam(required = false) String result,
                               @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime from,
                               @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime to,
                               @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE) int page,
                               @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE_SIZE) int size) {
        return service.search(username, action, resourceType, resourceId, ipAddress, buyerCode, factoryCode, result, from, to, page, size);
    }
    @GetMapping("/{id}")
    public AuditLog detail(@PathVariable String id, @RequestParam(required = false) String factoryCode) {
        return service.getById(id, factoryCode);
    }
}
