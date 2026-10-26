package org.bsl.cartonloading.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BuyerTypeAccessTest {
    @Test
    void onlyKnownSystemTypesAreAccepted() {
        assertEquals(SystemType.RFID, SystemType.normalize("RFID"));
        assertEquals(SystemType.UNRFID, SystemType.normalize("UnRFID"));
        assertEquals(SystemType.FACTORY_USE_ONLY, SystemType.normalize("Factory Use Only"));
        assertEquals("", SystemType.normalize("invalid"));
    }
    @Test
    void oldUsersKeepOnlyTheirHistoricBuyerWorkflow() {
        User user = new User();
        user.setRole("USER");
        user.setBuyerPermissions(List.of("LULULEMON", "ES"));
        assertTrue(user.canAccessBuyerType("LULULEMON", "RFID"));
        assertTrue(user.canAccessBuyerType("ES", "FACTORY_USE_ONLY"));
        assertFalse(user.canAccessBuyerType("ES", "RFID"));
        assertFalse(user.canAccessBuyerType("LULULEMON", "UNRFID"));
    }
    @Test
    void explicitTypeGrantsDoNotFallbackToLegacyBuyers() {
        User user = new User();
        user.setBuyerPermissions(List.of("LULULEMON", "ES"));
        user.setBuyerTypePermissions(List.of(new BuyerTypePermission("LULULEMON", List.of("RFID"))));
        assertTrue(user.canAccessBuyerType("LULULEMON", "RFID"));
        assertFalse(user.canAccessBuyerType("ES", "FACTORY_USE_ONLY"));
        user.setBuyerTypePermissions(List.of());
        assertFalse(user.canAccessBuyerType("LULULEMON", "RFID"));
    }
    @Test
    void unrfidIsRecognizedButNotImplemented() {
        assertFalse(SystemType.hasImplementedWorkflow("LULULEMON", "UNRFID"));
        assertTrue(SystemType.hasImplementedWorkflow("LULULEMON", "RFID"));
        assertTrue(SystemType.hasImplementedWorkflow("ES", "FACTORY_USE_ONLY"));
    }
}
