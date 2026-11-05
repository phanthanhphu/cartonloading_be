package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PoIdentityRegistryServiceTest {
    @Test void keyIgnoresCaseAndRepeatedWhitespace() {
        assertEquals(PoIdentityRegistryService.identityId(AppConstants.Buyer.LULULEMON,
                        "  MP  01 ", " PO123 ", "PP-A", "SO-1"),
                PoIdentityRegistryService.identityId(AppConstants.Buyer.LULULEMON,
                        "mp 01", "po123", "pp-a", "so-1"));
    }

    @Test void factoryDoesNotParticipateInIdentity() {
        var f1 = new PoIdentityRegistryService.Input("MP1", "PO1", "PP1", "SO1", "F1", "A");
        var f3 = new PoIdentityRegistryService.Input("MP1", "PO1", "PP1", "SO1", "F3", "B");
        assertEquals(PoIdentityRegistryService.identityId(AppConstants.Buyer.LULULEMON, f1.masterPo(), f1.poNumber(), f1.packingPlan(), f1.salesOrderPts()),
                PoIdentityRegistryService.identityId(AppConstants.Buyer.LULULEMON, f3.masterPo(), f3.poNumber(), f3.packingPlan(), f3.salesOrderPts()));
    }

    @Test void changingAnyOfFourFieldsChangesIdentity() {
        String base = PoIdentityRegistryService.identityId("LULULEMON", "MP1", "PO1", "PP1", "SO1");
        assertNotEquals(base, PoIdentityRegistryService.identityId("LULULEMON", "MP2", "PO1", "PP1", "SO1"));
        assertNotEquals(base, PoIdentityRegistryService.identityId("LULULEMON", "MP1", "PO2", "PP1", "SO1"));
        assertNotEquals(base, PoIdentityRegistryService.identityId("LULULEMON", "MP1", "PO1", "PP2", "SO1"));
        assertNotEquals(base, PoIdentityRegistryService.identityId("LULULEMON", "MP1", "PO1", "PP1", "SO2"));
        assertNotEquals(base, PoIdentityRegistryService.identityId("OTHER", "MP1", "PO1", "PP1", "SO1"));
    }
}
