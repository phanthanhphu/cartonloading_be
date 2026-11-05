package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.common.constants.AppMessages;
import org.bsl.cartonloading.buyers.core.BuyerWorkflowRegistry;
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

    public AuthController(UserService userService, JwtUtil jwtUtil, PasswordEncoder passwordEncoder, BuyerService buyerService, BuyerWorkflowRegistry workflowRegistry) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
        this.buyerService = buyerService;
        this.workflowRegistry = workflowRegistry;
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
