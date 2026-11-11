package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.common.constants.AppConstants;

import jakarta.validation.Valid;
import org.bsl.cartonloading.dto.UserDTO;
import org.bsl.cartonloading.dto.admin.PasswordResetRequest;
import org.bsl.cartonloading.dto.admin.UserAdminRequest;
import org.bsl.cartonloading.service.UserAdminService;
import org.bsl.cartonloading.service.UserAvatarService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("@accessControl.isAdmin()")
public class UserController {
    private final UserAdminService service;
    private final UserAvatarService avatars;
    public UserController(UserAdminService service, UserAvatarService avatars) {
        this.service = service;
        this.avatars = avatars;
    }

    @GetMapping
    public Page<UserDTO> list(@RequestParam(required = false) String username,
                              @RequestParam(required = false) String email,
                              @RequestParam(required = false) String phone,
                              @RequestParam(required = false) String address,
                              @RequestParam(required = false) String role,
                              @RequestParam(required = false) String departmentId,
                              @RequestParam(required = false) Boolean enabled,
                              @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE) int page,
                              @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE_SIZE) int size) {
        return service.list(username, email, phone, address, role, departmentId, enabled, page, size);
    }

    @PostMapping public UserDTO create(@Valid @RequestBody UserAdminRequest request) { return service.create(request); }
    @PutMapping("/{id}") public UserDTO update(@PathVariable String id, @Valid @RequestBody UserAdminRequest request) { return service.update(id, request); }
    /** Admin Add/Edit User and optional avatar in a single Save action. */
    @PostMapping(value = "/with-avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserDTO createWithAvatar(@Valid @RequestPart("user") UserAdminRequest request,
            @RequestPart(value = "avatar", required = false) MultipartFile avatar) {
        // Reject an invalid photo BEFORE creating the account.
        byte[] normalized = avatar == null ? null : UserAvatarService.normalizeAvatar(avatar);
        return service.createWithAvatar(request, normalized);
    }

    @PutMapping(value = "/{id}/with-avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserDTO updateWithAvatar(@PathVariable String id,
            @Valid @RequestPart("user") UserAdminRequest request,
            @RequestPart(value = "avatar", required = false) MultipartFile avatar,
            @RequestParam(value = "removeAvatar", defaultValue = "false") boolean removeAvatar) {
        if (removeAvatar && avatar != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot upload and remove the photo at the same time");
        }
        // Validate file before changing user fields or the old photo.
        byte[] normalized = avatar == null ? null : UserAvatarService.normalizeAvatar(avatar);
        UserDTO saved = service.update(id, request);
        try {
            if (normalized != null) avatars.saveUserAvatar(id, normalized);
            else if (removeAvatar) avatars.removeUserAvatar(id);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "User details were saved, but the photo change failed. Reopen Edit User and retry.", ex);
        }
        return saved;
    }

    /** Images are private: Admin uses authenticated blob download, no public image link. */
    @GetMapping(value = "/{id}/avatar", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> userAvatar(@PathVariable String id) {
        return avatars.readUserAvatar(id)
            .map(jpeg -> ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.IMAGE_JPEG).body(jpeg))
            .orElseGet(() -> ResponseEntity.noContent()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").build());
    }

    @PutMapping("/{id}/password") public ResponseEntity<Map<String, Object>> resetPassword(@PathVariable String id, @Valid @RequestBody PasswordResetRequest request) {
        service.resetPassword(id, request.password());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{id}/password/generate")
    public ResponseEntity<Map<String, String>> generatePassword(@PathVariable String id) {
        String password = service.generateAndResetPassword(id);
        return ResponseEntity.ok(Map.of("password", password));
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable String id) { service.delete(id); return ResponseEntity.noContent().build(); }
}
