package org.bsl.cartonloading.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.factory.FactoryCodes;
import org.bsl.cartonloading.dto.admin.FactoryRequest;
import org.bsl.cartonloading.model.Factory;
import org.bsl.cartonloading.repository.FactoryRepository;
import org.springframework.stereotype.Service;
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
    public FactoryCatalogService(FactoryRepository repository, AuditLogService audit, AppSocketPublisher socketPublisher) {
        this.repository = repository; this.audit = audit; this.socketPublisher = socketPublisher;
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
        audit.log(RequestActor.current(), AppConstants.AuditAction.CREATE, AppConstants.AuditResource.FACTORY,
                saved.getId(), "Factory " + code, null, null);
        socketPublisher.organizationChanged(AppConstants.OrganizationRealtime.FACTORY, AppConstants.AuditAction.CREATE);
        return saved;
    }
    public Factory update(String id, FactoryRequest request) {
        Factory f = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Factory not found"));
        if (!FactoryCodes.requireValid(request.code()).equals(f.getCode()))
            throw new IllegalArgumentException("Factory Code is immutable (used by operational history)");
        f.setName(request.name().trim());
        if (request.active() != null) f.setActive(request.active());
        f.setUpdatedAt(LocalDateTime.now());
        Factory saved = repository.save(f);
        audit.log(RequestActor.current(), AppConstants.AuditAction.UPDATE, AppConstants.AuditResource.FACTORY,
                saved.getId(), "Factory " + f.getCode() + " active=" + f.isActive(), null, null);
        socketPublisher.organizationChanged(AppConstants.OrganizationRealtime.FACTORY, AppConstants.AuditAction.UPDATE);
        return saved;
    }
}
