package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.common.constants.AppMessages;
import org.bsl.cartonloading.buyers.core.BuyerWorkflowRegistry;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.model.Buyer;
import org.bsl.cartonloading.model.Factory;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.bsl.cartonloading.service.AuditLogService;
import org.springframework.security.core.Authentication;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.model.SystemType;
import org.bsl.cartonloading.security.JwtUtil;
import org.bsl.cartonloading.service.BuyerService;
import org.bsl.cartonloading.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final BuyerService buyerService;
    private final BuyerWorkflowRegistry workflowRegistry;
    private final FactoryCatalogService factoryCatalog;
    private final AuditLogService audit;

    public AuthController(UserService userService, JwtUtil jwtUtil, PasswordEncoder passwordEncoder, BuyerService buyerService, BuyerWorkflowRegistry workflowRegistry, FactoryCatalogService factoryCatalog, AuditLogService audit) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
        this.buyerService = buyerService;
        this.workflowRegistry = workflowRegistry;
        this.factoryCatalog = factoryCatalog;
        this.audit = audit;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest) {
        String email = loginRequest.getEmail() == null ? "" : loginRequest.getEmail().trim();
        String password = loginRequest.getPassword();
        if (email.isEmpty() || password == null || password.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("message", AppMessages.INVALID_CREDENTIALS));
        }

        Optional<User> userOpt = userService.findByEmail(email);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("message", AppMessages.INVALID_CREDENTIALS));
        }

        User user = userOpt.get();
        if (!user.isEnabled()) {
            return ResponseEntity.status(403).body(Map.of("message", AppMessages.ACCOUNT_DISABLED));
        }
        if (user.getPassword() == null || !passwordEncoder.matches(password, user.getPassword())) {
            return ResponseEntity.status(401).body(Map.of("message", AppMessages.INVALID_CREDENTIALS));
        }
        String selectedBuyer = BuyerAccess.normalize(loginRequest.getBuyerCode());
        if (selectedBuyer.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", AppMessages.SELECT_VALID_BUYER));
        }
        if (!buyerService.existsActive(selectedBuyer)) {
            return ResponseEntity.status(403).body(Map.of("message", AppMessages.SELECTED_BUYER_INACTIVE));
        }
        if (!user.canAccessBuyer(selectedBuyer)) {
            return ResponseEntity.status(403)
                    .body(Map.of("message", AppMessages.SELECTED_BUYER_ACCESS_DENIED));
        }
        if (!user.hasAnyAccessForBuyer(selectedBuyer)) {
            return ResponseEntity.status(403).body(Map.of("message", "No active permissions for selected Buyer. Contact administrator."));
        }

        String selectedType = SystemType.normalize(loginRequest.getSystemType());
        if (selectedType.isEmpty()) return ResponseEntity.badRequest().body(Map.of("message", "Please select a valid system Type"));
        if (!buyerService.supportsType(selectedBuyer, selectedType))
            return ResponseEntity.status(403).body(Map.of("message", "Buyer " + selectedBuyer + " does not support " + selectedType));
        if (!workflowRegistry.hasImplementedWorkflow(selectedBuyer, selectedType))
            return ResponseEntity.status(403).body(Map.of("message", "This Buyer / Type workflow has not been implemented yet"));
        if (!user.canAccessBuyerType(selectedBuyer, selectedType))
            return ResponseEntity.status(403).body(Map.of("message", "You do not have access to Buyer " + selectedBuyer + " with Type " + selectedType));

        String token = jwtUtil.generateToken(
                user.getEmail(),
                user.getRole(),
                user.getTokenVersion(),
                user.getAccessPermissions(),
                user.getBuyerPermissions(),
                selectedBuyer, selectedType
        );
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", token);
        response.put("user", userService.toUserDTO(user));
        response.put("selectedBuyer", selectedBuyer);
        response.put("selectedType", selectedType);
        return ResponseEntity.ok(response);
    }

    /**
     * Server-authorized workspace choices. Never trust localStorage or login-options
     * for permission decisions. Only ACTIVE Buyers, approved Types, and implemented
     * workflows are returned. The LULULEMON Factory catalog is the only one
     * currently backed by a shared Factory master.
     */
    @GetMapping("/context-options")
    public ResponseEntity<?> contextOptions(Authentication authentication) {
        User user = authenticatedUser(authentication);
        if (user == null) return ResponseEntity.status(401).body(Map.of("message", "Session is no longer valid"));
        return ResponseEntity.ok(Map.of("options", authorizedContexts(user)));
    }

    @PostMapping("/switch-context")
    public ResponseEntity<?> switchContext(@RequestBody SwitchContextRequest request, Authentication authentication) {
        User user = authenticatedUser(authentication);
        if (user == null) return ResponseEntity.status(401).body(Map.of("message", "Session is no longer valid"));
        if (request == null) return ResponseEntity.badRequest().body(Map.of("message", "Select Buyer and Type"));

        String selectedBuyer = BuyerAccess.normalize(request.getBuyerCode());
        String selectedType = SystemType.normalize(request.getSystemType());
        String selectedFactory = User.normalizeFactoryCode(request.getFactoryCode());
        if (selectedBuyer.isEmpty() || selectedType.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("message", "Select a valid Buyer and Type"));

        // Re-evaluate permissions and Buyer state from MongoDB on each Apply.
        ContextOption authorized = authorizedContexts(user).stream()
                .filter(option -> option.buyerKey().equals(selectedBuyer))
                .findFirst().orElse(null);
        if (authorized == null || !authorized.supportedTypes().contains(selectedType))
            return ResponseEntity.status(403).body(Map.of("message", "Buyer / Type is not approved for this account"));

        if (AppConstants.Buyer.LULULEMON.equals(selectedBuyer)) {
            if (!selectedFactory.isEmpty() && !authorized.factories().contains(selectedFactory))
                return ResponseEntity.status(403).body(Map.of("message", "Factory is inactive or not assigned"));
            if (!authorized.factories().isEmpty() && selectedFactory.isEmpty())
                return ResponseEntity.badRequest().body(Map.of("message", "Please select an active Factory"));
        } else if (!selectedFactory.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Factory catalog is not available for this Buyer"));
        }

        String token = jwtUtil.generateToken(user.getEmail(), user.getRole(), user.getTokenVersion(),
                user.getAccessPermissions(), user.getBuyerPermissions(), selectedBuyer, selectedType);
        // Log the context change without logging JWT or credentials.
        audit.logChange(user.getEmail(), "SWITCH_CONTEXT", AppConstants.AuditResource.USER, user.getId(),
                "Switched workspace to " + selectedBuyer + " / " + selectedType
                        + (selectedFactory.isEmpty() ? "" : " / " + selectedFactory),
                selectedBuyer, selectedFactory.isEmpty() ? null : selectedFactory,
                null, null, null, null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", token);
        response.put("user", userService.toUserDTO(user));
        response.put("selectedBuyer", selectedBuyer);
        response.put("selectedType", selectedType);
        response.put("selectedFactory", selectedFactory);
        return ResponseEntity.ok(response);
    }

    private User authenticatedUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null || authentication.getName().isBlank()) return null;
        return userService.findByEmail(authentication.getName()).filter(User::isEnabled).orElse(null);
    }

    private List<ContextOption> authorizedContexts(User user) {
        return buyerService.loginOptions().stream()
                .filter(Buyer::isActive)
                .filter(buyer -> user.canAccessBuyer(buyer.getBuyerKey())
                        && user.hasAnyAccessForBuyer(buyer.getBuyerKey()))
                .map(buyer -> {
                    String buyerCode = BuyerAccess.normalize(buyer.getBuyerKey());
                    List<String> types = buyer.getSupportedTypes().stream()
                            .filter(type -> user.canAccessBuyerType(buyerCode, type)
                                    && workflowRegistry.hasImplementedWorkflow(buyerCode, type))
                            .toList();
                    if (types.isEmpty()) return null;
                    List<String> factories = AppConstants.Buyer.LULULEMON.equals(buyerCode)
                            ? factoryCatalog.list(buyerCode, false).stream()
                                .map(Factory::getCode)
                                .filter(user::canAccessFactory)
                                .toList()
                            : List.of();
                    return new ContextOption(buyerCode, buyer.getBuyerName(), buyer.getSlug(),
                            types, factories);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public record ContextOption(String buyerKey, String buyerName, String slug,
                                List<String> supportedTypes, List<String> factories) { }

    public static class SwitchContextRequest {
        private String buyerCode;
        private String systemType;
        private String factoryCode;
        public String getBuyerCode() { return buyerCode; }
        public void setBuyerCode(String buyerCode) { this.buyerCode = buyerCode; }
        public String getSystemType() { return systemType; }
        public void setSystemType(String systemType) { this.systemType = systemType; }
        public String getFactoryCode() { return factoryCode; }
        public void setFactoryCode(String factoryCode) { this.factoryCode = factoryCode; }
    }

    /** Read-only revoke notice. Works with signed, unexpired stale JWTs so a disconnected client can show the reason. */
    @GetMapping("/account-change")
    public ResponseEntity<?> accountChange(@org.springframework.web.bind.annotation.RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return ResponseEntity.status(401).build();
        String token = authorization.substring(7).trim();
        if (!jwtUtil.validateToken(token)) return ResponseEntity.status(401).build();
        String email = jwtUtil.getEmailFromToken(token);
        if (email == null) return ResponseEntity.status(401).build();
        User user = userService.findByEmail(email).orElse(null);
        if (user == null) return ResponseEntity.ok(Map.of("changed", true, "enabled", false,
                "details", List.of("This account no longer exists or its email address has changed")));
        long claimedVersion = jwtUtil.getTokenVersionFromToken(token);
        if (claimedVersion == user.getTokenVersion() && user.isEnabled()) return ResponseEntity.ok(Map.of("changed", false));
        List<String> details = user.getLastAccessChangeVersion() > claimedVersion
                ? user.getLastAccessChangeDetails() : List.of();
        if (details.isEmpty()) details = List.of(user.isEnabled()
                ? "Your access permissions or account details have been updated"
                : "Your account has been disabled");
        return ResponseEntity.ok(Map.of("changed", true, "userId", user.getId(),
                "version", user.getTokenVersion(), "enabled", user.isEnabled(), "details", details));
    }

    public static class LoginRequest {
        private String email;
        private String password;
        private String buyerCode;
        private String systemType;
        public String getSystemType() { return systemType; }
        public void setSystemType(String systemType) { this.systemType = systemType; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getBuyerCode() { return buyerCode; }
        public void setBuyerCode(String buyerCode) { this.buyerCode = buyerCode; }
    }
}
