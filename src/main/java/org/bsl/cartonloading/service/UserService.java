package org.bsl.cartonloading.service;

import org.bsl.cartonloading.dto.UserDTO;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Minimal user lookup used by JWT authentication and Carton Loading login. */
@Service
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Optional<User> findByEmail(String email) {
        if (email == null) return Optional.empty();
        String normalized = email.trim();
        if (normalized.isEmpty()) return Optional.empty();
        return userRepository.findByEmailIgnoreCase(normalized);
    }

    public UserDTO toUserDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setRole(user.getRole());
        dto.setAddress(user.getAddress());
        dto.setPhone(user.getPhone());
        dto.setEnabled(user.isEnabled());
        dto.setDepartmentId(user.getDepartmentId());
        dto.setAccessPermissions(user.getAccessPermissions());
        dto.setCanManageSales(user.canManageSales());
        dto.setCanAssignBarcode(user.canAssignBarcode());
        dto.setCanWeightCheck(user.canWeightCheck());
        dto.setViewOnly(user.isViewOnly());
        dto.setBuyerPermissions(user.getBuyerPermissions());
        dto.setFactoryPermissions(user.getFactoryPermissions());
        return dto;
    }
}
