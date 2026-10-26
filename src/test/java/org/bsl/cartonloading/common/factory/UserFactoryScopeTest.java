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
}
