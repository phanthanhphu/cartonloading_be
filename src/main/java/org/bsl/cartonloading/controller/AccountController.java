package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.AccountService;
import org.bsl.cartonloading.service.UserAvatarService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Shared controller: users can view their own avatar; only Admin can modify it in User Management. */
@RestController
@RequestMapping(AppConstants.ApiPath.ACCOUNT)
public class AccountController {
    private final AccountService service;
    private final UserAvatarService avatars;

    public AccountController(AccountService service, UserAvatarService avatars) {
        this.service = service;
        this.avatars = avatars;
    }

    @GetMapping("/me")
    public Map<String, Object> me() { return service.myProfile(); }

    @PutMapping("/password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody PasswordChangeRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password fields are required");
        service.changePassword(request.currentPassword(), request.newPassword(), request.confirmPassword());
        return ResponseEntity.ok(Map.of("success", true, "reloginRequired", true));
    }

    /** Private avatar download: Axios supplies the bearer token, never expose a public URL. */
    @GetMapping(value = "/avatar", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> avatar() {
        return avatars.readOwnAvatar()
                .map(jpeg -> ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .contentType(MediaType.IMAGE_JPEG)
                        .body(jpeg))
                .orElseGet(() -> ResponseEntity.noContent()
                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                        .build());
    }

    public record PasswordChangeRequest(String currentPassword, String newPassword, String confirmPassword) { }
}
