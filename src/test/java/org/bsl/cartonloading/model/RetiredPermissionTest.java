package org.bsl.cartonloading.model;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RetiredPermissionTest {
    @Test void existingPrintRoomOnlyAccountReceivesNoReplacementPrivileges() {
        assertEquals(List.of(), User.normalizeAccessPermissions(List.of("PRINT_ROOM"), false));
        assertEquals(List.of(AppConstants.Permission.VIEW_SYSTEM), User.normalizeAccessPermissions(List.of(), false));
        User user = new User();
        assertThrows(IllegalArgumentException.class,
                () -> user.setAccessPermissions(List.of("PRINT_ROOM")));
    }

    @Test void otherValidGrantsAreKeptWhileRetiredPermissionIsDropped() {
        assertEquals(List.of(AppConstants.Permission.WEIGHT_CHECK),
                User.normalizeAccessPermissions(List.of("PRINT_ROOM", AppConstants.Permission.WEIGHT_CHECK), false));
        assertFalse(User.normalizeAccessPermissions(List.of(), true).contains("PRINT_ROOM"));
    }
}
