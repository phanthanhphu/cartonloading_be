package org.bsl.cartonloading.service;

import org.bsl.cartonloading.model.AuditLog;
import org.bsl.cartonloading.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class AuditLogService {

    @Autowired
    private AuditLogRepository repository;

    public AuditLog log(
            String username,
            String action,
            String resourceType,
            String resourceId,
            String description,
            String ipAddress,
            String userAgent
    ) {

        AuditLog log = new AuditLog();

        log.setUsername(username);
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setDescription(description);
        log.setIpAddress(ipAddress);
        log.setUserAgent(userAgent);
        log.setCreatedAt(LocalDateTime.now());

        return repository.save(log);
    }

    public Page<AuditLog> getAll(int page, int size) {
        return search(null, null, null, null, null, page, size);
    }

    public Page<AuditLog> search(String username, String action, String resourceType, String resourceId,
                                 String ipAddress, int page, int size) {
        String userKey = clean(username);
        String actionKey = clean(action);
        String resourceKey = clean(resourceType);
        String resourceIdKey = clean(resourceId);
        String ipKey = clean(ipAddress);
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.max(1, Math.min(size, 200)),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        List<AuditLog> rows = repository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .filter(row -> userKey == null || contains(row.getUsername(), userKey))
                .filter(row -> actionKey == null || contains(row.getAction(), actionKey))
                .filter(row -> resourceKey == null || contains(row.getResourceType(), resourceKey))
                .filter(row -> resourceIdKey == null || contains(row.getResourceId(), resourceIdKey))
                .filter(row -> ipKey == null || contains(row.getIpAddress(), ipKey))
                .toList();
        int from = Math.min((int) pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
    }

    private String clean(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private boolean contains(String value, String q) {
        return value != null && q != null && value.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT));
    }

}