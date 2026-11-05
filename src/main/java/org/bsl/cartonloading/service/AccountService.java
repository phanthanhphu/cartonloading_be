package org.bsl.cartonloading.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.constants.AppMessages;
import org.bsl.cartonloading.dto.UserDTO;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.UserRepository;
import org.bsl.cartonloading.security.WorkspaceSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Self-service account operations. Identity is always derived from the verified JWT. */
@Service
public class AccountService {
    private final UserRepository users;
    private final UserService userService;
    private final PasswordEncoder encoder;
    private final AuditLogService audit;

    public AccountService(UserRepository users, UserService userService, PasswordEncoder encoder, AuditLogService audit) {
        this.users = users;
        this.userService = userService;
        this.encoder = encoder;
        this.audit = audit;
    }

    public Map<String, Object> myProfile() {
        UserDTO user = userService.toUserDTO(currentUser());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth.getDetails() instanceof WorkspaceSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, AppMessages.AUTHENTICATION_REQUIRED_LOGIN_AGAIN);
        }
        return Map.of("user", user, "selectedBuyer", session.buyer(), "selectedType", session.type());
    }

    public void changePassword(String currentPassword, String newPassword, String confirmPassword) {
        User user = currentUser();
        if (currentPassword == null || currentPassword.isEmpty() || user.getPassword() == null
                || !encoder.matches(currentPassword, user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, AppMessages.CURRENT_PASSWORD_INCORRECT);
        }
        if (newPassword == null || newPassword.length() < AppConstants.Security.MIN_PASSWORD_LENGTH
                || newPassword.length() > AppConstants.Security.MAX_PASSWORD_LENGTH || newPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, AppMessages.PASSWORD_LENGTH);
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, AppMessages.PASSWORD_CONFIRMATION_MISMATCH);
        }
        if (encoder.matches(newPassword, user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, AppMessages.NEW_PASSWORD_MUST_DIFFER);
        }
        user.setPassword(encoder.encode(newPassword));
        // JwtAuthenticationFilter verifies tokenVersion on EVERY request. Re-login is mandatory.
        user.setTokenVersion(user.getTokenVersion() + 1);
        users.save(user);
        // Do not log any password or password hash.
        audit.log(RequestActor.current(), AppConstants.AuditAction.CHANGE_OWN_PASSWORD,
                AppConstants.AuditResource.USER, user.getId(), "User changed own password", null, null);
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, AppMessages.AUTHENTICATION_REQUIRED_LOGIN_AGAIN);
        }
        return users.findByEmailIgnoreCase(auth.getName()).filter(User::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, AppMessages.AUTHENTICATION_REQUIRED_LOGIN_AGAIN));
    }
}
