package org.bsl.cartonloading.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.factory.FactoryCodes;
import org.bsl.cartonloading.dto.admin.FactoryRequest;
import org.bsl.cartonloading.model.Factory;
import org.bsl.cartonloading.repository.FactoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.bsl.cartonloading.common.socket.AppSocketPublisher;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/** Canonical source for active Factory choices; never fabricate hardcoded F1..F7. */
@Service
public class FactoryCatalogService {
    private final FactoryRepository repository;
    private final AuditLogService audit;
    private final AppSocketPublisher socketPublisher;
    private final FactoryUsageService factoryUsage;
    private final MongoTemplate mongoTemplate;
    public FactoryCatalogService(FactoryRepository repository, AuditLogService audit,
                                 AppSocketPublisher socketPublisher, FactoryUsageService factoryUsage,
                                 MongoTemplate mongoTemplate) {
        this.repository = repository; this.audit = audit; this.socketPublisher = socketPublisher;
        this.factoryUsage = factoryUsage; this.mongoTemplate = mongoTemplate;
    }
    public List<Factory> list(String buyerKey, boolean includeInactive) {
        if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(buyerKey))
            throw new IllegalArgumentException("Factory catalog is not configured for this buyer");
        return repository.findByBuyerKey(AppConstants.Buyer.LULULEMON).stream()
                .filter(f -> includeInactive || f.isActive())
                .sorted(Comparator.comparing(Factory::getCode, Comparator.comparingInt(FactoryCatalogService::number)))
                .toList();
    }
    private static int number(String code) {
        try { return Integer.parseInt(code.substring(1)); } catch (RuntimeException e) { return Integer.MAX_VALUE; }
    }
    public Factory requireActive(String buyerKey, String factoryCode) {
        String code = FactoryCodes.requireValid(factoryCode);
        Factory f = repository.findByBuyerKeyAndCode(buyerKey, code)
                .orElseThrow(() -> new IllegalArgumentException("Factory does not exist: " + code));
        if (!f.isActive()) throw new IllegalArgumentException("Factory is INACTIVE: " + code);
        return f;
    }
    public Factory create(FactoryRequest request) {
        String code = FactoryCodes.requireValid(request.code());
        if (repository.findByBuyerKeyAndCode(AppConstants.Buyer.LULULEMON, code).isPresent())
            throw new IllegalArgumentException("Factory already exists: " + code);
        Factory f = new Factory();
        f.setCode(code); f.setName(request.name().trim());
        f.setActive(Boolean.TRUE.equals(request.active()));
        f.setCreatedAt(LocalDateTime.now()); f.setUpdatedAt(f.getCreatedAt());
        Factory saved = repository.save(f);
        audit.logChange(RequestActor.current(), AppConstants.AuditAction.CREATE, AppConstants.AuditResource.FACTORY,
                saved.getId(), "Factory " + code, AppConstants.Buyer.LULULEMON, code, null, saved, null, null);
        socketPublisher.organizationChanged(AppConstants.OrganizationRealtime.FACTORY, AppConstants.AuditAction.CREATE);
        return saved;
    }
    /** ADMIN only (controller). Never cascade/delete referenced operational records. */
    public void delete(String id) {
        Factory factory = repository.findById(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Factory not found"));
        String code = FactoryCodes.requireValid(factory.getCode());
        List<String> usage = factoryUsage.findUsage(code);
        if (!usage.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Factory " + code + " cannot be deleted because it is used by: "
                            + String.join(", ", usage) + ". Remove its assignments/dependencies first, or deactivate it.");
        }
        java.util.Map<String, Object> before = audit.snapshot(factory);
        repository.delete(factory);
        audit.logChange(RequestActor.current(), AppConstants.AuditAction.DELETE, AppConstants.AuditResource.FACTORY,
                factory.getId(), "Factory " + code, AppConstants.Buyer.LULULEMON,
                code, before, null, null, null);
        socketPublisher.organizationChanged(AppConstants.OrganizationRealtime.FACTORY, AppConstants.AuditAction.DELETE);
    }

    /** Change only the status atomically; do not overwrite a concurrently edited Factory name. */
    public Factory updateStatus(String id, boolean active) {
        Factory before = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Factory not found"));
        if (before.isActive() == active) return before;
        java.util.Map<String, Object> snapshot = audit.snapshot(before);
        Query query = Query.query(Criteria.where("_id").is(id));
        Update update = new Update().set("active", active).set("updatedAt", LocalDateTime.now());
        Factory saved = mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), Factory.class);
        if (saved == null) throw new IllegalArgumentException("Factory not found");
        audit.logChange(RequestActor.current(), AppConstants.AuditAction.UPDATE, AppConstants.AuditResource.FACTORY,
                saved.getId(), "Factory " + saved.getCode() + " active=" + saved.isActive(),
                AppConstants.Buyer.LULULEMON, saved.getCode(), snapshot, saved, null, null);
        socketPublisher.organizationChanged(AppConstants.OrganizationRealtime.FACTORY, AppConstants.AuditAction.UPDATE);
        return saved;
    }

    public Factory update(String id, FactoryRequest request) {
        Factory f = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Factory not found"));
        if (!FactoryCodes.requireValid(request.code()).equals(f.getCode()))
            throw new IllegalArgumentException("Factory Code is immutable (used by operational history)");
        java.util.Map<String, Object> before = audit.snapshot(f);
        f.setName(request.name().trim());
        if (request.active() != null) f.setActive(request.active());
        f.setUpdatedAt(LocalDateTime.now());
        Factory saved = repository.save(f);
        audit.logChange(RequestActor.current(), AppConstants.AuditAction.UPDATE, AppConstants.AuditResource.FACTORY,
                saved.getId(), "Factory " + f.getCode() + " active=" + f.isActive(), AppConstants.Buyer.LULULEMON,
                f.getCode(), before, saved, null, null);
        socketPublisher.organizationChanged(AppConstants.OrganizationRealtime.FACTORY, AppConstants.AuditAction.UPDATE);
        return saved;
    }
}
