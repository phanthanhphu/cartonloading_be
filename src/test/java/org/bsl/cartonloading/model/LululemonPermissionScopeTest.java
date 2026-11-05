package org.bsl.cartonloading.model;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LululemonPermissionScopeTest {
    private User user(String buyer, String... permissions) {
        User u = new User();
        u.setRole("USER");
        u.setBuyerPermissions(List.of(buyer));
        u.setBuyerAccessPermissions(Map.of(AppConstants.Buyer.LULULEMON, List.of(permissions)));
        u.setFactoryPermissions(List.of("F1"));
        return u;
    }

    @Test void scannerCannotUploadOrScheduleOrEnterAnotherFactory() {
        User u = user("LULULEMON", AppConstants.Permission.LULU_SCAN_RFID_SSCC_SKU_WEIGHT);
        assertTrue(u.canLululemonScanWeight());
        assertFalse(u.canLululemonUploadEdit());
        assertFalse(u.canLululemonShippingScheduler());
        assertFalse(u.canLululemonReopenShippingSchedule());
        assertFalse(u.canLululemonCreateShippingSchedule());
        assertTrue(u.canAccessFactory("F1"));
        assertFalse(u.canAccessFactory("F3"));
    }

    @Test void schedulerCannotUploadOrScan() {
        User u = user("LULULEMON", AppConstants.Permission.LULU_SHIPPING_SCHEDULER);
        assertTrue(u.canLululemonShippingScheduler());
        assertFalse(u.canLululemonReopenShippingSchedule());
        assertFalse(u.canLululemonUploadEdit());
        assertFalse(u.canLululemonScanWeight());
    }

    @Test void createIsIndependentOfSchedulerAndReopen() {
        User creator = user("LULULEMON", AppConstants.Permission.LULU_SHIPPING_SCHEDULE_CREATE);
        assertTrue(creator.canLululemonCreateShippingSchedule());
        assertTrue(creator.canLululemonRead());
        assertFalse(creator.canLululemonShippingScheduler());
        assertFalse(creator.canLululemonReopenShippingSchedule());
        User editor = user("LULULEMON", AppConstants.Permission.LULU_SHIPPING_SCHEDULER);
        assertFalse(editor.canLululemonCreateShippingSchedule());
        creator.setBuyerAccessPermissions(Map.of("LULULEMON", List.of()));
        assertFalse(creator.canLululemonCreateShippingSchedule());
        assertThrows(IllegalArgumentException.class, () -> creator.setBuyerAccessPermissions(Map.of(
                "LULULEMON", List.of(AppConstants.Permission.LULU_VIEW_ONLY, AppConstants.Permission.LULU_SHIPPING_SCHEDULE_CREATE))));
    }

    @Test void reopenIsAnExplicitBuyerScopedPermissionNotInferredFromScheduler() {
        User supervisor = user("LULULEMON", AppConstants.Permission.LULU_SHIPPING_SCHEDULE_REOPEN);
        assertTrue(supervisor.canLululemonReopenShippingSchedule());
        assertTrue(supervisor.canLululemonRead());
        assertFalse(supervisor.canLululemonShippingScheduler()); // Unlock != modify PO assignments.
        assertFalse(supervisor.canLululemonUploadEdit());
        assertFalse(supervisor.canLululemonScanWeight());
        User combined = user("LULULEMON", AppConstants.Permission.LULU_SHIPPING_SCHEDULER,
                AppConstants.Permission.LULU_SHIPPING_SCHEDULE_REOPEN);
        assertTrue(combined.canLululemonShippingScheduler());
        assertTrue(combined.canLululemonReopenShippingSchedule());
        supervisor.setBuyerAccessPermissions(Map.of("LULULEMON", List.of()));
        assertFalse(supervisor.canLululemonReopenShippingSchedule()); // Revoking takes effect.
    }

    @Test void viewOnlyCannotWriteAndCannotCombineWithWrites() {
        User u = user("LULULEMON", AppConstants.Permission.LULU_VIEW_ONLY);
        assertTrue(u.canLululemonRead());
        assertFalse(u.canLululemonUploadEdit());
        assertFalse(u.canLululemonScanWeight());
        assertFalse(u.canLululemonShippingScheduler());
        assertFalse(u.canLululemonReopenShippingSchedule());
        assertThrows(IllegalArgumentException.class, () -> u.setBuyerAccessPermissions(Map.of(
                "LULULEMON", List.of(AppConstants.Permission.LULU_VIEW_ONLY, AppConstants.Permission.LULU_UPLOAD_EDIT_DATA))));
    }

    @Test void viewOnlyCannotBeGrantedReopenAtTheSameTime() {
        User u = user("LULULEMON", AppConstants.Permission.LULU_VIEW_ONLY);
        assertThrows(IllegalArgumentException.class, () -> u.setBuyerAccessPermissions(Map.of(
                "LULULEMON", List.of(AppConstants.Permission.LULU_VIEW_ONLY,
                        AppConstants.Permission.LULU_SHIPPING_SCHEDULE_REOPEN))));
    }

    @Test void esLegacyGrantsCannotOverrideExplicitLuluPermissions() {
        User u = user("LULULEMON", AppConstants.Permission.LULU_VIEW_ONLY);
        u.setAccessPermissions(List.of(AppConstants.Permission.SALES, AppConstants.Permission.WEIGHT_CHECK));
        assertTrue(u.canManageSales()); // ES legacy behavior unchanged
        assertFalse(u.canLululemonUploadEdit());
        assertFalse(u.canLululemonScanWeight());
        assertFalse(u.canLululemonReopenShippingSchedule());
    }

    @Test void emptyExplicitPermissionsDoNotInheritLegacyWriteGrants() {
        User u = user("LULULEMON");
        u.setAccessPermissions(List.of(AppConstants.Permission.SALES));
        assertFalse(u.hasAnyAccessForBuyer("LULULEMON"));
    }
}
