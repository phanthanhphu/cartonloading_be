package org.bsl.cartonloading.service;

import org.bsl.cartonloading.dto.UserDTO;
import org.bsl.cartonloading.dto.admin.UserAdminRequest;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.DepartmentRepository;
import org.bsl.cartonloading.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class UserAdminService {
    private static final SecureRandom PASSWORD_RANDOM = new SecureRandom();
    // Avoid visually ambiguous characters so the temporary password is easier to copy/type.
    private static final String PASSWORD_UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String PASSWORD_LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String PASSWORD_DIGITS = "23456789";
    private static final String PASSWORD_ALPHABET = PASSWORD_UPPER + PASSWORD_LOWER + PASSWORD_DIGITS;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserService userService;
    private final AuditLogService audit;

    public UserAdminService(UserRepository userRepository, DepartmentRepository departmentRepository,
                            PasswordEncoder passwordEncoder, UserService userService, AuditLogService audit) {
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.userService = userService;
        this.audit = audit;
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
        if (clean(request.password()) == null) throw new IllegalArgumentException("Password is required");
        if (userRepository.findByEmailIgnoreCase(request.email().trim()).isPresent()) throw new IllegalArgumentException("Email already exists");
        User user = new User();
        user.setCreatedAt(LocalDateTime.now());
        user.setTokenVersion(1L);
        apply(user, request, true);
        User saved = userRepository.save(user);
        audit.log(RequestActor.current(), "CREATE", "USER", saved.getId(), "Created user " + saved.getEmail(), null, null);
        return userService.toUserDTO(saved);
    }

    public UserDTO update(String id, UserAdminRequest request) {
        User user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));
        userRepository.findByEmailIgnoreCase(request.email().trim())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> { throw new IllegalArgumentException("Email already exists"); });
        apply(user, request, false);
        user.setTokenVersion(user.getTokenVersion() + 1);
        User saved = userRepository.save(user);
        audit.log(RequestActor.current(), "UPDATE", "USER", saved.getId(), "Updated user " + saved.getEmail(), null, null);
        return userService.toUserDTO(saved);
    }

    public void resetPassword(String id, String password) {
        User user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));
        savePassword(user, validatePassword(password), "RESET_PASSWORD");
    }

    /**
     * Generate the temporary password on the server, persist exactly that value as BCrypt,
     * verify the persisted hash immediately, and return the raw value once to the Admin UI.
     */
    public String generateAndResetPassword(String id) {
        User user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));
        String password = generateTemporaryPassword(14);
        savePassword(user, password, "GENERATE_RESET_PASSWORD");
        return password;
    }

    private void savePassword(User user, String rawPassword, String action) {
        String encoded = passwordEncoder.encode(rawPassword);
        if (!passwordEncoder.matches(rawPassword, encoded)) {
            throw new IllegalStateException("Password encoding verification failed");
        }

        user.setPassword(encoded);
        user.setTokenVersion(user.getTokenVersion() + 1);
        User saved = userRepository.save(user);

        // Verify what was actually persisted, not only the in-memory encoded value.
        if (!passwordEncoder.matches(rawPassword, saved.getPassword())) {
            throw new IllegalStateException("Password was not persisted correctly");
        }

        audit.log(RequestActor.current(), action, "USER", saved.getId(),
                "Reset password for " + saved.getEmail(), null, null);
    }

    private String validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 200) {
            throw new IllegalArgumentException("Password must be between 8 and 200 characters");
        }
        if (password.trim().isEmpty()) {
            throw new IllegalArgumentException("Password cannot be blank");
        }
        return password;
    }

    private String generateTemporaryPassword(int length) {
        int safeLength = Math.max(12, length);
        char[] chars = new char[safeLength];
        chars[0] = PASSWORD_UPPER.charAt(PASSWORD_RANDOM.nextInt(PASSWORD_UPPER.length()));
        chars[1] = PASSWORD_LOWER.charAt(PASSWORD_RANDOM.nextInt(PASSWORD_LOWER.length()));
        chars[2] = PASSWORD_DIGITS.charAt(PASSWORD_RANDOM.nextInt(PASSWORD_DIGITS.length()));
        for (int i = 3; i < safeLength; i++) {
            chars[i] = PASSWORD_ALPHABET.charAt(PASSWORD_RANDOM.nextInt(PASSWORD_ALPHABET.length()));
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
        User user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (user.isAdminRole() && userRepository.findAll().stream().filter(User::isAdminRole).count() <= 1) {
            throw new IllegalArgumentException("Cannot delete the last Admin account");
        }
        userRepository.delete(user);
        audit.log(RequestActor.current(), "DELETE", "USER", id, "Deleted user " + user.getEmail(), null, null);
    }

    private void apply(User user, UserAdminRequest request, boolean creating) {
        user.setUsername(required(request.username(), "Username is required"));
        user.setEmail(required(request.email(), "Email is required").toLowerCase(Locale.ROOT));
        user.setAddress(clean(request.address()));
        user.setPhone(clean(request.phone()));
        user.setRole(User.normalizeRole(request.role()));
        user.setEnabled(request.enabled() == null || request.enabled());

        String departmentId = clean(request.departmentId());
        if (departmentId != null && !departmentRepository.existsById(departmentId)) {
            throw new IllegalArgumentException("Department not found");
        }
        user.setDepartmentId(departmentId);
        user.setAccessPermissions(request.accessPermissions());
        user.setBuyerPermissions(request.buyerPermissions());
        user.setFactoryPermissions(request.factoryPermissions());

        if (!user.isAdminRole() && user.getBuyerPermissions().isEmpty()) {
            throw new IllegalArgumentException("At least one Buyer permission is required");
        }
        if (!user.isAdminRole() && request.buyerPermissions() != null) {
            for (String buyer : user.getBuyerPermissions()) {
                if (!BuyerAccess.isSupported(buyer)) throw new IllegalArgumentException("Unsupported Buyer permission: " + buyer);
            }
        }
        if (creating || clean(request.password()) != null) {
            user.setPassword(passwordEncoder.encode(required(request.password(), "Password is required")));
        }
    }

    private String required(String value, String message) { String v = clean(value); if (v == null) throw new IllegalArgumentException(message); return v; }
    private String clean(String value) { if (value == null) return null; String v = value.trim().replaceAll("\\s+", " "); return v.isEmpty() ? null : v; }
    private boolean contains(String value, String q) { return value != null && value.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)); }
}
