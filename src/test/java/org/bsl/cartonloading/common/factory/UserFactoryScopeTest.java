package org.bsl.cartonloading.common.factory;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.model.User;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UserFactoryScopeTest {
    @Test void nonAdminSalesAndViewOnlyRemainFactoryScoped() {
        User user = new User();
        user.setRole(AppConstants.Role.USER);
        user.setFactoryPermissions(List.of("F8"));
        user.setAccessPermissions(List.of(AppConstants.Permission.SALES));
        assertTrue(user.canAccessFactory("F8"));
        assertFalse(user.canAccessFactory("F1"));
        user.setAccessPermissions(List.of(AppConstants.Permission.VIEW_SYSTEM));
        assertFalse(user.canAccessFactory("F2"));
    }
    @Test void adminSeesAllFactoryScopesAndExplicitPermissionsWork() {
        User user = new User();
        user.setRole(AppConstants.Role.ADMIN);
        assertTrue(user.canAccessFactory("F99"));
        user.setRole(AppConstants.Role.USER);
        user.setAccessPermissions(List.of(AppConstants.Permission.ASSIGN_WEIGHT));
        assertTrue(user.canAssignWeight());
        assertFalse(user.canWeightCheck());
    }
    @Test void pickPoIsSeparatelyAssignableWithoutRemovingLegacyPackingRights() {
        User user = new User();
        user.setAccessPermissions(List.of(AppConstants.Permission.PICK_PO));
        assertTrue(user.canPickPo());
        assertFalse(user.canAssignBarcode());
        user.setAccessPermissions(List.of(AppConstants.Permission.ASSIGN_BARCODE));
        assertTrue(user.canPickPo());
    }
    @Test void weighingAssignmentsCanGoBeyondSeven() {
        User user = new User();
        user.setFactoryPermissions(List.of("F18"));
        user.setWeighingFactoryPermissions(List.of("F18"));
        user.setAccessPermissions(List.of(AppConstants.Permission.WEIGHT_CHECK));
        assertTrue(user.canAccessWeighingFactory("F18"));
        assertFalse(user.canAccessWeighingFactory("F2"));
    }
    @Test void usersInSameFactoryReceiveIndependentPermissionsAndDepartmentIsNotAnotherGrant() {
        User scanner = new User();
        scanner.setRole(AppConstants.Role.USER);
        scanner.setBuyerPermissions(List.of(AppConstants.Buyer.LULULEMON));
        scanner.setFactoryPermissions(List.of("F1"));
        scanner.setDepartmentId("dept-f1");
        scanner.setBuyerAccessPermissions(java.util.Map.of(AppConstants.Buyer.LULULEMON,
                List.of(AppConstants.Permission.LULU_SCAN_RFID_SSCC_SKU_WEIGHT)));

        User viewer = new User();
        viewer.setRole(AppConstants.Role.USER);
        viewer.setBuyerPermissions(List.of(AppConstants.Buyer.LULULEMON));
        viewer.setFactoryPermissions(List.of("F1"));
        viewer.setDepartmentId("dept-f1");
        viewer.setBuyerAccessPermissions(java.util.Map.of(AppConstants.Buyer.LULULEMON,
                List.of(AppConstants.Permission.LULU_VIEW_ONLY)));

        assertTrue(scanner.canAccessFactory("F1"));
        assertTrue(viewer.canAccessFactory("F1"));
        assertFalse(scanner.canAccessFactory("F2"));
        assertFalse(viewer.canAccessFactory("F2"));
        assertTrue(scanner.canLululemonScanWeight());
        assertFalse(viewer.canLululemonScanWeight());
        // Department metadata never grants cross-Factory access or Scan rights.
        viewer.setDepartmentId("dept-f2");
        assertFalse(viewer.canAccessFactory("F2"));
        assertFalse(viewer.canLululemonScanWeight());
    }
}
