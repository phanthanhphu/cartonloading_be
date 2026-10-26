package org.bsl.cartonloading.service;

import org.bsl.cartonloading.common.constants.AppMessages;
import org.bsl.cartonloading.common.constants.AppConstants;
import jakarta.annotation.PostConstruct;
import org.bsl.cartonloading.dto.admin.BuyerAdminRequest;
import org.bsl.cartonloading.model.Buyer;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.BuyerRepository;
import org.bsl.cartonloading.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BuyerService {
    private final BuyerRepository buyerRepository;
    private final UserRepository userRepository;
    private final AuditLogService audit;

    public BuyerService(BuyerRepository buyerRepository, UserRepository userRepository, AuditLogService audit) {
        this.buyerRepository = buyerRepository;
        this.userRepository = userRepository;
        this.audit = audit;
    }

    @PostConstruct
    public void seedCartonLoadingBuyers() {
        // Seed defaults only for a brand-new database. Once admins manage Buyers,
        // a deleted Buyer must not be recreated on every application restart.
        if (buyerRepository.count() > 0) return;
        int sequence = 10;
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, String> entry : AppConstants.Buyer.DEFAULT_LABELS.entrySet()) {
            Buyer buyer = buyerRepository.findByBuyerKeyIgnoreCase(entry.getKey()).orElseGet(Buyer::new);
            if (buyer.getId() == null) {
                buyer.setBuyerKey(entry.getKey());
                buyer.setCreatedAt(now);
                buyer.setCreatedBy("SYSTEM");
                buyer.setActive(true);
                buyer.setSupportedTypes(org.bsl.cartonloading.model.SystemType.legacyBuyerTypes(entry.getKey()));
            }
            if (buyer.getBuyerName() == null || buyer.getBuyerName().isBlank()) buyer.setBuyerName(entry.getValue());
            if (buyer.getSlug() == null || buyer.getSlug().isBlank()) buyer.setSlug(slugify(entry.getValue()));
            if (buyer.getSequence() <= 0) buyer.setSequence(sequence);
            if (buyer.getDescription() == null) buyer.setDescription("Carton Loading workspace");
            buyer.setUpdatedAt(now);
            buyer.setUpdatedBy("SYSTEM");
            buyerRepository.save(buyer);
            sequence += 10;
        }
    }

    public List<Buyer> loginOptions() {
        return buyerRepository.findByActiveTrueOrderBySequenceAscBuyerNameAsc().stream()
                .toList();
    }

    public List<Buyer> accessible() {
        User user = currentUser();
        List<Buyer> active = loginOptions();
        if (user.isAdminRole()) return active;
        org.springframework.security.core.Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof org.bsl.cartonloading.security.WorkspaceSession session))
            return List.of();
        return active.stream().filter(item -> item.getBuyerKey().equals(session.buyer())
                && user.canAccessBuyerType(item.getBuyerKey(), session.type())).toList();
    }

    public Page<Buyer> list(String buyerKey, String buyerName, String description, Boolean active, int page, int size) {
        String keyFilter = clean(buyerKey);
        String nameFilter = clean(buyerName);
        String descriptionFilter = clean(description);
        List<Buyer> rows = buyerRepository.findAllByOrderBySequenceAscBuyerNameAsc().stream()
                .filter(item -> active == null || item.isActive() == active)
                .filter(item -> keyFilter == null || contains(item.getBuyerKey(), keyFilter))
                .filter(item -> nameFilter == null || contains(item.getBuyerName(), nameFilter))
                .filter(item -> descriptionFilter == null || contains(item.getDescription(), descriptionFilter))
                .collect(Collectors.toList());
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200)));
        int from = Math.min((int) pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
    }

    public Buyer get(String id) { return buyerRepository.findById(id).orElseThrow(() -> new IllegalArgumentException(AppMessages.BUYER_NOT_FOUND)); }

    public Buyer create(BuyerAdminRequest request) {
        String key = BuyerAccess.normalize(request.buyerKey());
        if (key.isBlank()) throw new IllegalArgumentException(AppMessages.BUYER_KEY_INVALID);
        if (buyerRepository.findByBuyerKeyIgnoreCase(key).isPresent()) throw new IllegalArgumentException(AppMessages.BUYER_KEY_EXISTS);
        Buyer buyer = new Buyer(); buyer.setBuyerKey(key); buyer.setCreatedAt(LocalDateTime.now()); buyer.setCreatedBy(RequestActor.current());
        apply(buyer, request); Buyer saved = buyerRepository.save(buyer);
        audit.log(RequestActor.current(), AppConstants.AuditAction.CREATE, AppConstants.AuditResource.BUYER, saved.getId(), AppMessages.buyerCreatedAudit(saved.getBuyerKey()), null, null);
        return saved;
    }

    public Buyer update(String id, BuyerAdminRequest request) {
        Buyer buyer = get(id);
        String key = BuyerAccess.normalize(request.buyerKey());
        if (!Objects.equals(key, buyer.getBuyerKey())) throw new IllegalArgumentException(AppMessages.BUYER_KEY_IMMUTABLE);
        apply(buyer, request); Buyer saved = buyerRepository.save(buyer);
        audit.log(RequestActor.current(), AppConstants.AuditAction.UPDATE, AppConstants.AuditResource.BUYER, saved.getId(), AppMessages.buyerUpdatedAudit(saved.getBuyerKey()), null, null);
        return saved;
    }

    public void delete(String id) {
        Buyer buyer = get(id);
        String key = buyer.getBuyerKey();
        boolean assigned = userRepository.findAll().stream()
                .filter(u -> !u.isAdminRole())
                .anyMatch(u -> u.getBuyerPermissions().contains(key));
        if (assigned) throw new IllegalArgumentException(AppMessages.BUYER_ASSIGNED_TO_USERS);
        buyerRepository.delete(buyer);
        audit.log(RequestActor.current(), AppConstants.AuditAction.DELETE, AppConstants.AuditResource.BUYER, id, AppMessages.buyerDeletedAudit(key), null, null);
    }

    public boolean supportsType(String buyerKey, String type) {
        String key = BuyerAccess.normalize(buyerKey);
        return buyerRepository.findByBuyerKeyIgnoreCase(key)
                .filter(Buyer::isActive).map(b -> b.supportsType(type)).orElse(false);
    }

    public boolean existsActive(String buyerKey) {
        String key = BuyerAccess.normalize(buyerKey);
        return !key.isBlank() && buyerRepository.findByBuyerKeyIgnoreCase(key).map(Buyer::isActive).orElse(false);
    }

    private void apply(Buyer buyer, BuyerAdminRequest request) {
        String name = clean(request.buyerName()); if (name == null) throw new IllegalArgumentException(AppMessages.BUYER_NAME_REQUIRED);
        buyer.setBuyerName(name); buyer.setSlug(slugify(name)); buyer.setActive(request.active() == null || request.active());
        buyer.setSequence(request.sequence() == null ? 0 : Math.max(0, request.sequence())); buyer.setDescription(clean(request.description()));
        if (request.supportedTypes() != null) {
            if (request.supportedTypes().stream().anyMatch(v -> org.bsl.cartonloading.model.SystemType.normalize(v).isEmpty()))
                throw new IllegalArgumentException("Invalid system type. Use RFID, UNRFID or FACTORY_USE_ONLY.");
            buyer.setSupportedTypes(request.supportedTypes());
        } else if (buyer.getId() == null) {
            buyer.setSupportedTypes(org.bsl.cartonloading.model.SystemType.legacyBuyerTypes(buyer.getBuyerKey()));
        }
        buyer.setUpdatedAt(LocalDateTime.now()); buyer.setUpdatedBy(RequestActor.current());
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) throw new IllegalArgumentException(AppMessages.AUTHENTICATION_REQUIRED);
        return userRepository.findByEmail(auth.getName()).filter(User::isEnabled).orElseThrow(() -> new IllegalArgumentException(AppMessages.CURRENT_USER_UNAVAILABLE));
    }
    private String slugify(String value) { String s = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('&',' ').replaceAll("[^a-z0-9]+","-").replaceAll("^-+|-+$",""); return s.isEmpty()?"buyer":s; }
    private String clean(String value) { if (value == null) return null; String v=value.trim().replaceAll("\\s+"," "); return v.isEmpty()?null:v; }
    private boolean contains(String value,String q){ return value != null && value.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)); }
}
