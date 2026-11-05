package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Shared controller: both ES and Lululemon users manage only their own account. */
@RestController
@RequestMapping(AppConstants.ApiPath.ACCOUNT)
public class AccountController {
    private final AccountService service;

    public AccountController(AccountService service) { this.service = service; }

    @GetMapping("/me")
    public Map<String, Object> me() { return service.myProfile(); }

    @PutMapping("/password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody PasswordChangeRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password fields are required");
        service.changePassword(request.currentPassword(), request.newPassword(), request.confirmPassword());
        return ResponseEntity.ok(Map.of("success", true, "reloginRequired", true));
    }

    public record PasswordChangeRequest(String currentPassword, String newPassword, String confirmPassword) { }
}
