package org.bsl.cartonloading.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsl.cartonloading.model.AuditLog;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.AuditLogRepository;
import org.bsl.cartonloading.repository.UserRepository;
import org.bsl.cartonloading.security.WorkspaceSession;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class AuditLogService {
    private static final int MAX_FIELDS = 80;
    // Exclude passwords, tokens, secrets and binary/raw device data from immutable audit snapshots.
    private static final Set<String> EXCLUDED = Set.of("password", "passwordhash", "token", "tokenversion",
            "accessToken", "refreshtoken", "authorization", "secret", "apikey", "privatekey",
            "credential", "credentials", "profileimageurl", "image", "data", "rawpayload", "epcs");
    private final AuditLogRepository repository;
    private final UserRepository users;
    private final MongoTemplate mongo;
    private final ObjectMapper mapper;

    public AuditLogService(AuditLogRepository repository, UserRepository users, MongoTemplate mongo, ObjectMapper mapper) {
        this.repository = repository; this.users = users; this.mongo = mongo; this.mapper = mapper;
    }

    /** Legacy writer, intentionally ADMIN-only until the action supplies a verified Factory. */
    public AuditLog log(String username, String action, String resourceType, String resourceId,
                        String description, String ipAddress, String userAgent) {
        return logChange(username, action, resourceType, resourceId, description, null, null,
                null, null, ipAddress, userAgent);
    }

    /** Explicit Buyer + Factory are sourced from the affected entity, NOT a client-supplied log payload. */
    public AuditLog logChange(String username, String action, String resourceType, String resourceId,
                              String description, String buyer, String factory,
                              Object before, Object after, String ipAddress, String userAgent) {
        AuditLog row = new AuditLog();
        row.setUsername(username);
        row.setAction(action);
        row.setResourceType(resourceType);
        row.setResourceId(resourceId);
        row.setDescription(description);
        row.setIpAddress(ipAddress);
        row.setUserAgent(userAgent);
        row.setCreatedAt(LocalDateTime.now());
        // Ensure Factory scope is unambiguous, and only issue it when Buyer context is explicit.
        String b = BuyerAccess.normalize(buyer);
        String f = User.normalizeFactoryCode(factory);
        if (!b.isEmpty()) row.setBuyerCode(b);
        if (!b.isEmpty() && !f.isEmpty() && f.matches("F[1-9][0-9]*")) row.setFactoryCode(f);
        Map<String, Object> oldValues = snapshot(before);
        Map<String, Object> newValues = snapshot(after);
        row.setBefore(oldValues.isEmpty() ? null : oldValues);
        row.setAfter(newValues.isEmpty() ? null : newValues);
        Map<String, Map<String, Object>> changed = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>(oldValues.keySet());
        names.addAll(newValues.keySet());
        for (String name : names) {
            Object oldValue = oldValues.get(name), newValue = newValues.get(name);
            if (!Objects.equals(oldValue, newValue)) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("before", oldValue); diff.put("after", newValue);
                changed.put(name, diff);
            }
        }
        row.setChanges(changed.isEmpty() ? null : changed);
        row.setResult("SUCCESS");
        return repository.save(row);
    }

    /** Snapshot before modifying the loaded entity: never pass the same mutated object as before and after. */
    public Map<String, Object> snapshot(Object value) {
        if (value == null) return Map.of();
        JsonNode node = mapper.valueToTree(value);
        if (!node.isObject()) return Map.of("value", scalar(node));
        Map<String, Object> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext() && result.size() < MAX_FIELDS) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (excluded(field.getKey())) continue;
            result.put(field.getKey(), scalar(field.getValue()));
        }
        return result;
    }
    private boolean excluded(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return EXCLUDED.stream().map(v -> v.toLowerCase(Locale.ROOT)).anyMatch(normalized::equals)
                || normalized.contains("password") || normalized.contains("secret")
                || normalized.contains("credential") || normalized.contains("apikey")
                || normalized.contains("privatekey") || normalized.contains("authorization")
                || normalized.endsWith("token") || normalized.startsWith("token");
    }
    private Object scalar(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) { String s = node.asText(); return s.length() > 500 ? s.substring(0, 500) + "…" : s; }
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNumber()) return node.numberValue();
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                if (!excluded(entry.getKey()) && map.size() < 30) {
                    JsonNode v = entry.getValue();
                    map.put(entry.getKey(), v.isContainerNode() ? "[nested data]" : scalar(v));
                }
            });
            return map;
        }
        if (node.isArray()) {
            List<Object> items = new ArrayList<>();
            for (JsonNode v : node) {
                if (items.size() >= 30) break;
                items.add(v.isContainerNode() ? "[nested data]" : scalar(v));
            }
            return items;
        }
        return "[unsupported]";
    }

    private record Viewer(User user, WorkspaceSession workspace) {}
    private Viewer viewer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getDetails() instanceof WorkspaceSession session))
            throw new AccessDeniedException("Valid workspace required");
        User user = users.findByEmail(auth.getName()).orElseThrow(() -> new AccessDeniedException("Unknown user"));
        if (!user.isEnabled() || !user.canAccessBuyerType(session.buyer(), session.type()))
            throw new AccessDeniedException("Buyer workspace access denied");
        if (!user.isAdminRole() && user.getFactoryPermissions().isEmpty())
            throw new AccessDeniedException("No assigned Factories");
        return new Viewer(user, session);
    }

    public Page<AuditLog> search(String username, String action, String resourceType, String resourceId,
                                 String ipAddress, String buyer, String factory, String result,
                                 LocalDateTime from, LocalDateTime to, int page, int size) {
        Viewer caller = viewer();
        String buyerFilter = clean(buyer), factoryFilter = clean(factory);
        List<Criteria> criteria = new ArrayList<>();
        if (!caller.user().isAdminRole()) {
            String activeBuyer = caller.workspace().buyer();
            if (buyerFilter != null && !activeBuyer.equals(BuyerAccess.normalize(buyerFilter)))
                throw new AccessDeniedException("Buyer access denied");
            criteria.add(Criteria.where("buyerCode").is(activeBuyer));
            // A non-admin may never query "All Factories" or legacy records without a Factory.
            List<String> allowedFactories = caller.user().getFactoryPermissions();
            String scopedFactory;
            if (factoryFilter != null) {
                scopedFactory = User.normalizeFactoryCode(factoryFilter);
                if (!allowedFactories.contains(scopedFactory))
                    throw new AccessDeniedException("Factory access denied");
            } else if (allowedFactories.size() == 1) {
                scopedFactory = allowedFactories.get(0);
            } else {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Select an assigned Factory to view audit logs");
            }
            criteria.add(Criteria.where("factoryCode").is(scopedFactory));
        } else {
            // Admin can query every Factory AND legacy audit rows without Factory information.
            if (buyerFilter != null) criteria.add(Criteria.where("buyerCode").is(BuyerAccess.normalize(buyerFilter)));
            if (factoryFilter != null) criteria.add(Criteria.where("factoryCode").is(User.normalizeFactoryCode(factoryFilter)));
        }
        addLike(criteria, "username", username);
        addLike(criteria, "action", action);
        addLike(criteria, "resourceType", resourceType);
        addLike(criteria, "resourceId", resourceId);
        addLike(criteria, "ipAddress", ipAddress);
        if (clean(result) != null) criteria.add(Criteria.where("result").is(clean(result).toUpperCase(Locale.ROOT)));
        if (from != null || to != null) {
            Criteria dates = Criteria.where("createdAt");
            if (from != null) dates.gte(from);
            if (to != null) dates.lte(to);
            criteria.add(dates);
        }
        Query query = new Query();
        if (!criteria.isEmpty()) query.addCriteria(new Criteria().andOperator(criteria.toArray(new Criteria[0])));
        long total = mongo.count(query, AuditLog.class);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200)), Sort.by(Sort.Direction.DESC, "createdAt"));
        query.with(pageable);
        return new PageImpl<>(mongo.find(query, AuditLog.class), pageable, total);
    }

    public AuditLog getById(String id, String requestedFactory) {
        Viewer caller = viewer();
        AuditLog log = repository.findById(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Audit log not found"));
        if (!caller.user().isAdminRole()) {
            List<String> allowedFactories = caller.user().getFactoryPermissions();
            String scopedFactory;
            if (clean(requestedFactory) != null) {
                scopedFactory = User.normalizeFactoryCode(requestedFactory);
                if (!allowedFactories.contains(scopedFactory))
                    throw new AccessDeniedException("Factory access denied");
            } else if (allowedFactories.size() == 1) {
                scopedFactory = allowedFactories.get(0);
            } else {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Select an assigned Factory to read an audit record");
            }
            // The detail endpoint cannot bypass the same current-Factory constraint as the list.
            if (!caller.workspace().buyer().equals(log.getBuyerCode()) ||
                    !scopedFactory.equals(log.getFactoryCode()))
                throw new AccessDeniedException("Factory audit access denied");
        }
        return log;
    }
    private static void addLike(List<Criteria> criteria, String key, String text) {
        if (clean(text) != null) criteria.add(Criteria.where(key).regex(Pattern.quote(text.trim()), "i"));
    }
    private static String clean(String v) { return v == null || v.trim().isEmpty() ? null : v.trim(); }
}
