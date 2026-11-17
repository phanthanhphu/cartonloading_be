package org.bsl.cartonloading.model;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Report scope never inherits generic ADMIN or workstation Factory access. */
class ManagementReportScopeTest {
    private User account(String role, String grant, String... factories) {
        User user = new User();
        user.setRole(role);
        user.setBuyerPermissions(List.of(AppConstants.Buyer.LULULEMON));
        user.setFactoryPermissions(List.of(factories));
        user.setBuyerAccessPermissions(Map.of(AppConstants.Buyer.LULULEMON,
                grant == null ? List.of() : List.of(grant)));
        return user;
    }

    @Test void adminIsNotAutoGrantedReports() {
        User admin = account(AppConstants.Role.ADMIN, null, "F1");
        assertFalse(admin.canLululemonViewManagementReports());
        assertFalse(admin.canLululemonExportManagementReports());
        assertFalse(admin.canLululemonReportAllFactories());
    }

    @Test void allFactoriesCanBeGrantedToAdminOrOrdinaryUserWithoutFactoryAssignment() {
        for (String role : List.of(AppConstants.Role.ADMIN, AppConstants.Role.USER)) {
            User user = account(role, AppConstants.Permission.LULU_MANAGEMENT_REPORT_ALL);
            assertTrue(user.canLululemonReportAllFactories());
            assertTrue(user.canLululemonViewManagementReports());
            assertTrue(user.canLululemonExportManagementReports());
        }
    }

    @Test void assignedFactoryScopeAppliesEvenToAdmin() {
        for (String role : List.of(AppConstants.Role.ADMIN, AppConstants.Role.USER)) {
            User user = account(role, AppConstants.Permission.LULU_MANAGEMENT_REPORT_ASSIGNED, "F1", "F3");
            assertFalse(user.canLululemonReportAllFactories());
            assertTrue(user.canLululemonReportAssignedFactories());
            assertTrue(user.canLululemonViewManagementReports());
            assertEquals(List.of("F1", "F3"), user.getFactoryPermissions());
        }
    }

    @Test void assignedWithoutOwnersHidesReport() {
        User admin = account(AppConstants.Role.ADMIN, AppConstants.Permission.LULU_MANAGEMENT_REPORT_ASSIGNED);
        assertFalse(admin.canLululemonViewManagementReports());
        assertFalse(admin.canLululemonExportManagementReports());
    }

    @Test void legacyViewAndExportDoNotImplicitlyGrantNewOptions() {
        for (String role : List.of(AppConstants.Role.ADMIN, AppConstants.Role.USER)) {
            for (String legacy : List.of(AppConstants.Permission.LULU_MANAGEMENT_REPORT_VIEW,
                    AppConstants.Permission.LULU_MANAGEMENT_REPORT_EXPORT)) {
                User user = account(role, legacy, "F1");
                assertFalse(user.canLululemonViewManagementReports());
                assertFalse(user.canLululemonReportAllFactories());
            }
        }
    }

    @Test void choosingBothScopesIsRejected() {
        User admin = account(AppConstants.Role.ADMIN, null, "F1");
        assertThrows(IllegalArgumentException.class, () -> admin.setBuyerAccessPermissions(Map.of(
                AppConstants.Buyer.LULULEMON, List.of(
                        AppConstants.Permission.LULU_MANAGEMENT_REPORT_ALL,
                        AppConstants.Permission.LULU_MANAGEMENT_REPORT_ASSIGNED))));
    }
}
