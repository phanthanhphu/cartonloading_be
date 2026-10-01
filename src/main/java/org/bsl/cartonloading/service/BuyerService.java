package org.bsl.cartonloading.service;

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
    private static final Map<String, String> CARTON_LOADING_BUYERS = new LinkedHashMap<>();
    static {
        CARTON_LOADING_BUYERS.put(BuyerAccess.LULULEMON, "LULULEMON");
        CARTON_LOADING_BUYERS.put(BuyerAccess.ENGELBERT_STRAUSS, "ENGELBERT STRAUSS");
    }

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
        for (Map.Entry<String, String> entry : CARTON_LOADING_BUYERS.entrySet()) {
            Buyer buyer = buyerRepository.findByBuyerKeyIgnoreCase(entry.getKey()).orElseGet(Buyer::new);
            if (buyer.getId() == null) {
                buyer.setBuyerKey(entry.getKey());
                buyer.setCreatedAt(now);
                buyer.setCreatedBy("SYSTEM");
                buyer.setActive(true);
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
                .filter(item -> BuyerAccess.isSupported(item.getBuyerKey()))
                .toList();
    }

    public List<Buyer> accessible() {
        User user = currentUser();
        List<Buyer> active = loginOptions();
        if (user.isAdminRole()) return active;
        Set<String> allowed = Set.copyOf(user.getBuyerPermissions());
        return active.stream().filter(item -> allowed.contains(item.getBuyerKey())).toList();
    }

    public Page<Buyer> list(String buyerKey, String buyerName, String description, Boolean active, int page, int size) {
        String keyFilter = clean(buyerKey);
        String nameFilter = clean(buyerName);
        String descriptionFilter = clean(description);
        List<Buyer> rows = buyerRepository.findAllByOrderBySequenceAscBuyerNameAsc().stream()
                .filter(item -> BuyerAccess.isSupported(item.getBuyerKey()))
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

    public Buyer get(String id) { return buyerRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Buyer not found")); }

    public Buyer create(BuyerAdminRequest request) {
        String key = BuyerAccess.normalize(request.buyerKey());
        if (!BuyerAccess.isSupported(key)) throw new IllegalArgumentException("Only Carton Loading buyers are supported: LULULEMON, ENGELBERT_STRAUSS");
        if (buyerRepository.findByBuyerKeyIgnoreCase(key).isPresent()) throw new IllegalArgumentException("Buyer Key already exists");
        Buyer buyer = new Buyer(); buyer.setBuyerKey(key); buyer.setCreatedAt(LocalDateTime.now()); buyer.setCreatedBy(RequestActor.current());
        apply(buyer, request); Buyer saved = buyerRepository.save(buyer);
        audit.log(RequestActor.current(), "CREATE", "BUYER", saved.getId(), "Created Buyer " + saved.getBuyerKey(), null, null);
        return saved;
    }

    public Buyer update(String id, BuyerAdminRequest request) {
        Buyer buyer = get(id);
        String key = BuyerAccess.normalize(request.buyerKey());
        if (!Objects.equals(key, buyer.getBuyerKey())) throw new IllegalArgumentException("Buyer Key cannot be changed");
        apply(buyer, request); Buyer saved = buyerRepository.save(buyer);
        audit.log(RequestActor.current(), "UPDATE", "BUYER", saved.getId(), "Updated Buyer " + saved.getBuyerKey(), null, null);
        return saved;
    }

    public void delete(String id) {
        Buyer buyer = get(id);
        String key = buyer.getBuyerKey();
        boolean assigned = userRepository.findAll().stream()
                .filter(u -> !u.isAdminRole())
                .anyMatch(u -> u.getBuyerPermissions().contains(key));
        if (assigned) throw new IllegalArgumentException("Buyer is assigned to one or more users. Remove the Buyer permission from those users first.");
        buyerRepository.delete(buyer);
        audit.log(RequestActor.current(), "DELETE", "BUYER", id, "Deleted Buyer " + key, null, null);
    }

    public boolean existsActive(String buyerKey) {
        String key = BuyerAccess.normalize(buyerKey);
        return BuyerAccess.isSupported(key) && buyerRepository.findByBuyerKeyIgnoreCase(key).map(Buyer::isActive).orElse(false);
    }

    private void apply(Buyer buyer, BuyerAdminRequest request) {
        String name = clean(request.buyerName()); if (name == null) throw new IllegalArgumentException("Buyer Name is required");
        buyer.setBuyerName(name); buyer.setSlug(slugify(name)); buyer.setActive(request.active() == null || request.active());
        buyer.setSequence(request.sequence() == null ? 0 : Math.max(0, request.sequence())); buyer.setDescription(clean(request.description()));
        buyer.setUpdatedAt(LocalDateTime.now()); buyer.setUpdatedBy(RequestActor.current());
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) throw new IllegalArgumentException("Authentication is required");
        return userRepository.findByEmail(auth.getName()).filter(User::isEnabled).orElseThrow(() -> new IllegalArgumentException("Current User is not available"));
    }
    private String slugify(String value) { String s = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('&',' ').replaceAll("[^a-z0-9]+","-").replaceAll("^-+|-+$",""); return s.isEmpty()?"buyer":s; }
    private String clean(String value) { if (value == null) return null; String v=value.trim().replaceAll("\\s+"," "); return v.isEmpty()?null:v; }
    private boolean contains(String value,String q){ return value != null && value.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)); }
}
