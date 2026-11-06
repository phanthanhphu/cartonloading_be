package org.bsl.cartonloading.service;

import org.bsl.cartonloading.model.BuyerTypePermission;
import org.bsl.cartonloading.model.SystemType;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.constants.AppMessages;
import org.bsl.cartonloading.dto.UserDTO;
import org.bsl.cartonloading.dto.admin.UserAdminRequest;
import org.bsl.cartonloading.model.Buyer;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.BuyerRepository;
import org.bsl.cartonloading.repository.DepartmentRepository;
import org.bsl.cartonloading.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.bsl.cartonloading.common.socket.AppSocketPublisher;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.List;
import java.util.Locale;

@Service
public class UserAdminService {
    private static final SecureRandom PASSWORD_RANDOM = new SecureRandom();
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final BuyerRepository buyerRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserService userService;
    private final AuditLogService audit;
    private final FactoryCatalogService factories;
    private final AppSocketPublisher socketPublisher;

    public UserAdminService(UserRepository userRepository, DepartmentRepository departmentRepository, BuyerRepository buyerRepository,
                            PasswordEncoder passwordEncoder, UserService userService, AuditLogService audit, FactoryCatalogService factories, AppSocketPublisher socketPublisher) {
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.buyerRepository = buyerRepository;
        this.passwordEncoder = passwordEncoder;
        this.userService = userService;
        this.audit = audit;
        this.factories = factories;
        this.socketPublisher = socketPublisher;
    }

    public Page<UserDTO> list(String username, String email, String phone, String address,
                                  String role, String departmentId, Boolean enabled, int page, int size) {
        String usernameKey = clean(username);
        String emailKey = clean(email);
        String phoneKey = clean(phone);
        String addressKey = clean(address);
        String roleKey = clean(role);
        String departmentKey = clean(departmentId);
        List<UserDTO> rows = userRepository.findAll().stream()
                .filter(u -> usernameKey == null || contains(u.getUsername(), usernameKey))
                .filter(u -> emailKey == null || contains(u.getEmail(), emailKey))
                .filter(u -> phoneKey == null || contains(u.getPhone(), phoneKey))
                .filter(u -> addressKey == null || contains(u.getAddress(), addressKey))
                .filter(u -> roleKey == null || u.getRole().equalsIgnoreCase(roleKey))
                .filter(u -> departmentKey == null || departmentKey.equals(u.getDepartmentId()))
                .filter(u -> enabled == null || u.isEnabled() == enabled)
                .sorted(Comparator.comparing(User::getUsername, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(userService::toUserDTO)
                .toList();
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200)));
        int from = Math.min((int) pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
    }

    public UserDTO create(UserAdminRequest request) {
        if (clean(request.password()) == null) throw new IllegalArgumentException(AppMessages.PASSWORD_REQUIRED);
        if (userRepository.findByEmailIgnoreCase(request.email().trim()).isPresent()) throw new IllegalArgumentException(AppMessages.EMAIL_ALREADY_EXISTS);
        User user = new User();
        user.setCreatedAt(LocalDateTime.now());
        user.setTokenVersion(1L);
        apply(user, request, true);
        User saved = userRepository.save(user);
        audit.logChange(RequestActor.current(), AppConstants.AuditAction.CREATE, AppConstants.AuditResource.USER, saved.getId(), AppMessages.userCreatedAudit(saved.getEmail()), null, null, null, saved, null, null);
        socketPublisher.organizationChanged(AppConstants.OrganizationRealtime.USER, AppConstants.AuditAction.CREATE);
        return userService.toUserDTO(saved);
    }

    public UserDTO update(String id, UserAdminRequest request) {
        User user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException(AppMessages.USER_NOT_FOUND));
        userRepository.findByEmailIgnoreCase(request.email().trim())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> { throw new IllegalArgumentException(AppMessages.EMAIL_ALREADY_EXISTS); });
        java.util.Map<String, Object> before = audit.snapshot(user);
        String priorEmail = user.getEmail();
        boolean wasEnabled = user.isEnabled();
        String priorRole = user.getRole();
        List<String> priorBuyers = List.copyOf(user.getBuyerPermissions());
        Map<String, List<String>> priorBuyerGrants = user.getBuyerAccessPermissions();
        List<String> priorEsGrants = List.copyOf(user.getAccessPermissions());
        List<String> priorFactories = List.copyOf(user.getFactoryPermissions());
        String priorDepartment = user.getDepartmentId();
        List<String> priorTypes = user.getBuyerTypePermissions().stream()
                .map(value -> value.buyerCode() + ": " + String.join(", ", value.types())).toList();
        apply(user, request, false);
        // Never let an Admin disable/demote the sole remaining ACTIVE administrator.
        if (AppConstants.Role.ADMIN.equals(priorRole) && wasEnabled
                && (!user.isAdminRole() || !user.isEnabled())
                && userRepository.findAll().stream().noneMatch(other -> !id.equals(other.getId())
                        && other.isAdminRole() && other.isEnabled())) {
            throw new IllegalArgumentException("Cannot disable or demote the last enabled Administrator");
        }
        List<String> changes = summarizeChange(user, wasEnabled, priorRole, priorBuyers,
                priorBuyerGrants, priorEsGrants, priorFactories, priorTypes, priorDepartment);
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setLastAccessChangeDetails(changes);
        user.setLastAccessChangeVersion(user.getTokenVersion());
        User saved = userRepository.save(user);
        audit.logChange(RequestActor.current(), AppConstants.AuditAction.UPDATE, AppConstants.AuditResource.USER, saved.getId(), AppMessages.userUpdatedAudit(saved.getEmail()), null, null, before, saved, null, null);
        socketPublisher.organizationChanged(AppConstants.OrganizationRealtime.USER, AppConstants.AuditAction.UPDATE);
        // Existing authenticated WebSocket principal is the former email if Admin edited it.
        socketPublisher.accountChanged(priorEmail, saved.getId(), saved.getTokenVersion(), saved.isEnabled(), changes);
        return userService.toUserDTO(saved);
    }

    private static void grantDiff(List<String> messages, String label, List<String> before, List<String> after) {
        LinkedHashSet<String> added = new LinkedHashSet<>(after);
        added.removeAll(before);
        LinkedHashSet<String> removed = new LinkedHashSet<>(before);
        removed.removeAll(after);
        if (!added.isEmpty()) messages.add("Granted " + label + ": " + String.join(", ", added));
        if (!removed.isEmpty()) messages.add("Revoked " + label + ": " + String.join(", ", removed));
    }

    private static List<String> summarizeChange(User user, boolean wasEnabled, String priorRole,
            List<String> priorBuyers, Map<String, List<String>> priorBuyerGrants,
            List<String> priorEsGrants, List<String> priorFactories, List<String> priorTypes,
            String priorDepartment) {
        List<String> messages = new ArrayList<>();
        if (wasEnabled != user.isEnabled()) messages.add(user.isEnabled()
                ? "Account enabled" : "Account disabled");
        if (!Objects.equals(priorRole, user.getRole()))
            messages.add("Role changed: " + priorRole + " → " + user.getRole());
        grantDiff(messages, "Buyer", priorBuyers, user.getBuyerPermissions());
        for (String buyer : new LinkedHashSet<>(java.util.stream.Stream.concat(
                priorBuyerGrants.keySet().stream(), user.getBuyerAccessPermissions().keySet().stream()).toList())) {
            grantDiff(messages, buyer + " / permissions", priorBuyerGrants.getOrDefault(buyer, List.of()),
                    user.getBuyerAccessPermissions().getOrDefault(buyer, List.of()));
        }
        grantDiff(messages, "ENGELBERT STRAUSS / permissions", priorEsGrants, user.getAccessPermissions());
        grantDiff(messages, "Factory", priorFactories, user.getFactoryPermissions());
        if (!Objects.equals(priorDepartment, user.getDepartmentId()))
            messages.add("Department assignment changed");
        grantDiff(messages, "Buyer / Type", priorTypes, user.getBuyerTypePermissions().stream()
                .map(value -> value.buyerCode() + ": " + String.join(", ", value.types())).toList());
        if (messages.isEmpty()) messages.add("Account details updated by administrator");
        return messages;
    }

    public void resetPassword(String id, String password) {
        User user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException(AppMessages.USER_NOT_FOUND));
        savePassword(user, validatePassword(password), AppConstants.AuditAction.RESET_PASSWORD);
    }

    /**
     * Generate the temporary password on the server, persist exactly that value as BCrypt,
     * verify the persisted hash immediately, and return the raw value once to the Admin UI.
     */
    public String generateAndResetPassword(String id) {
        User user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException(AppMessages.USER_NOT_FOUND));
        String password = generateTemporaryPassword(14);
        savePassword(user, password, AppConstants.AuditAction.GENERATE_RESET_PASSWORD);
        return password;
    }

    private void savePassword(User user, String rawPassword, String action) {
        String encoded = passwordEncoder.encode(rawPassword);
        if (!passwordEncoder.matches(rawPassword, encoded)) {
            throw new IllegalStateException(AppMessages.PASSWORD_ENCODING_FAILED);
        }

        user.setPassword(encoded);
        user.setTokenVersion(user.getTokenVersion() + 1);
        List<String> changes = List.of("Password reset by administrator");
        user.setLastAccessChangeDetails(changes);
        user.setLastAccessChangeVersion(user.getTokenVersion());
        User saved = userRepository.save(user);

        // Verify what was actually persisted, not only the in-memory encoded value.
        if (!passwordEncoder.matches(rawPassword, saved.getPassword())) {
            throw new IllegalStateException(AppMessages.PASSWORD_NOT_PERSISTED);
        }

        audit.log(RequestActor.current(), action, AppConstants.AuditResource.USER, saved.getId(),
                AppMessages.userPasswordResetAudit(saved.getEmail()), null, null);
        socketPublisher.organizationChanged(AppConstants.OrganizationRealtime.USER, action);
        socketPublisher.accountChanged(saved.getEmail(), saved.getId(), saved.getTokenVersion(), saved.isEnabled(), changes);
    }

    private String validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 200) {
            throw new IllegalArgumentException(AppMessages.PASSWORD_LENGTH);
        }
        if (password.trim().isEmpty()) {
            throw new IllegalArgumentException(AppMessages.PASSWORD_NOT_BLANK);
        }
        return password;
    }

    private String generateTemporaryPassword(int length) {
        int safeLength = Math.max(12, length);
        char[] chars = new char[safeLength];
        chars[0] = AppConstants.Security.TEMP_PASSWORD_UPPER.charAt(PASSWORD_RANDOM.nextInt(AppConstants.Security.TEMP_PASSWORD_UPPER.length()));
        chars[1] = AppConstants.Security.TEMP_PASSWORD_LOWER.charAt(PASSWORD_RANDOM.nextInt(AppConstants.Security.TEMP_PASSWORD_LOWER.length()));
        chars[2] = AppConstants.Security.TEMP_PASSWORD_DIGITS.charAt(PASSWORD_RANDOM.nextInt(AppConstants.Security.TEMP_PASSWORD_DIGITS.length()));
        for (int i = 3; i < safeLength; i++) {
            chars[i] = AppConstants.Security.TEMP_PASSWORD_ALPHABET.charAt(PASSWORD_RANDOM.nextInt(AppConstants.Security.TEMP_PASSWORD_ALPHABET.length()));
        }
        for (int i = chars.length - 1; i > 0; i--) {
            int j = PASSWORD_RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }

    public void delete(String id) {
        User user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException(AppMessages.USER_NOT_FOUND));
        if (user.isAdminRole() && userRepository.findAll().stream().filter(User::isAdminRole).count() <= 1) {
            throw new IllegalArgumentException(AppMessages.LAST_ADMIN_DELETE_BLOCKED);
        }
        userRepository.delete(user);
        audit.logChange(RequestActor.current(), AppConstants.AuditAction.DELETE, AppConstants.AuditResource.USER, id, AppMessages.userDeletedAudit(user.getEmail()), null, null, user, null, null, null);
        socketPublisher.organizationChanged(AppConstants.OrganizationRealtime.USER, AppConstants.AuditAction.DELETE);
        // Deleted accounts must also be pushed out immediately; their JWTs are rejected
        // by JwtAuthenticationFilter because the user no longer exists.
        socketPublisher.accountChanged(user.getEmail(), user.getId(), user.getTokenVersion() + 1,
                false, List.of("Account deleted by administrator"));
    }

    private void apply(User user, UserAdminRequest request, boolean creating) {
        user.setUsername(required(request.username(), AppMessages.USERNAME_REQUIRED));
        user.setEmail(required(request.email(), AppMessages.EMAIL_REQUIRED).toLowerCase(Locale.ROOT));
        user.setAddress(clean(request.address()));
        user.setPhone(clean(request.phone()));
        user.setRole(User.normalizeRole(request.role()));
        user.setEnabled(request.enabled() == null || request.enabled());

        String departmentId = clean(request.departmentId());
        if (departmentId != null && !departmentRepository.existsById(departmentId)) {
            throw new IllegalArgumentException(AppMessages.DEPARTMENT_NOT_FOUND);
        }
        user.setDepartmentId(departmentId);
        user.setAccessPermissions(request.accessPermissions());
        user.setBuyerPermissions(request.buyerPermissions());
        if (!user.isAdminRole() && user.canAccessBuyer(AppConstants.Buyer.ENGELBERT_STRAUSS)
                && (request.accessPermissions() == null || request.accessPermissions().isEmpty()))
            throw new IllegalArgumentException("Select at least one ENGELBERT STRAUSS Access Permission");
        // Null is for old API clients; Edit User always posts explicit Buyer-scoped grants.
        if (request.buyerAccessPermissions() != null) {
            user.setBuyerAccessPermissions(request.buyerAccessPermissions());
        }
        user.removeUnassignedBuyerAccessPermissions();
        if (!user.isAdminRole() && user.canAccessBuyer(AppConstants.Buyer.LULULEMON)
                && !user.canLululemonRead())
            throw new IllegalArgumentException("Select at least one LULULEMON Access Permission");
        if (request.buyerTypePermissions() != null) {
            user.setBuyerTypePermissions(request.buyerTypePermissions());
        } else if (creating) {
            // Older API clients can create accounts with just Buyer permissions;
            // default only to the two pre-existing, implemented workflow grants.
            user.setBuyerTypePermissions(user.getBuyerPermissions().stream()
                    .filter(b -> !SystemType.legacyBuyerTypes(b).isEmpty())
                    .map(b -> new BuyerTypePermission(
                            b, SystemType.legacyBuyerTypes(b))).toList());
        }

        user.setFactoryPermissions(request.factoryPermissions());
        if (request.weighingFactoryPermissions() != null) user.setWeighingFactoryPermissions(request.weighingFactoryPermissions());

        if (!user.isAdminRole() && user.canAccessBuyer(AppConstants.Buyer.LULULEMON)) {
            if (user.getFactoryPermissions().isEmpty())
                throw new IllegalArgumentException("Assign at least one active Factory to LULULEMON user");
            for (String code : user.getFactoryPermissions()) factories.requireActive(AppConstants.Buyer.LULULEMON, code);
            for (String code : user.getWeighingFactoryPermissions()) {
                factories.requireActive(AppConstants.Buyer.LULULEMON, code);
                if (!user.getFactoryPermissions().contains(code))
                    throw new IllegalArgumentException("Weighing Factory must also be in assigned Factories: " + code);
            }
        }
        if (!user.isAdminRole() && departmentId != null) {
            // A Department is organisational metadata. It must never point at a Factory
            // outside the User's assigned scope, even when the user has another Buyer.
            String departmentFactory = departmentRepository.findById(departmentId).orElseThrow().getFactory();
            if (!user.getFactoryPermissions().contains(departmentFactory))
                throw new IllegalArgumentException("Department Factory must belong to the User's assigned Factories");
        }
        if (!user.isAdminRole() && user.getBuyerPermissions().isEmpty()) {
            throw new IllegalArgumentException(AppMessages.BUYER_PERMISSION_REQUIRED);
        }
        if (!user.isAdminRole() && request.buyerPermissions() != null) {
            for (String buyerCode : user.getBuyerPermissions()) {
                Buyer buyer = buyerRepository.findByBuyerKeyIgnoreCase(buyerCode)
                        .orElseThrow(() -> new IllegalArgumentException(AppMessages.buyerDoesNotExist(buyerCode)));
                if (!buyer.isActive()) {
                    throw new IllegalArgumentException(AppMessages.buyerInactive(buyerCode));
                }
            }
        }
        if (!user.isAdminRole()) {
            for (BuyerTypePermission grant : user.getBuyerTypePermissions()) {
                if (!user.getBuyerPermissions().contains(grant.buyerCode()))
                    throw new IllegalArgumentException("Type grant Buyer not assigned: " + grant.buyerCode());
                Buyer buyer = buyerRepository.findByBuyerKeyIgnoreCase(grant.buyerCode())
                        .orElseThrow(() -> new IllegalArgumentException("Buyer not found: " + grant.buyerCode()));
                for (String type : grant.types()) {
                    if (!buyer.supportsType(type))
                        throw new IllegalArgumentException("Buyer " + grant.buyerCode() + " does not support " + type);
                }
            }
            for (String buyerCode : user.getBuyerPermissions()) {
                if (user.getBuyerTypePermissions().stream()
                        .noneMatch(grant -> buyerCode.equals(grant.buyerCode()) && !grant.types().isEmpty()))
                    throw new IllegalArgumentException("Select at least one System Type for Buyer " + buyerCode);
            }
        }
        if (creating || clean(request.password()) != null) {
            user.setPassword(passwordEncoder.encode(required(request.password(), AppMessages.PASSWORD_REQUIRED)));
        }
    }

    private String required(String value, String message) { String v = clean(value); if (v == null) throw new IllegalArgumentException(message); return v; }
    private String clean(String value) { if (value == null) return null; String v = value.trim().replaceAll("\\s+", " "); return v.isEmpty() ? null : v; }
    private boolean contains(String value, String q) { return value != null && value.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)); }
}
