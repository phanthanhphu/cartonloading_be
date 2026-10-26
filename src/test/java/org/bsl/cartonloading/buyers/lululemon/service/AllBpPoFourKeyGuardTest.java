package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AllBpPoFourKeyGuardTest {
    private static PurchaseOrder po(String id, String masterPo, String poNumber,
                                    String packingPlan, String salesOrderPts) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(id);
        po.setMasterPo(masterPo);
        po.setPoNumber(poNumber);
        po.setPackingPlan(packingPlan);
        po.setSalesOrderPts(salesOrderPts);
        return po;
    }

    @Test
    void sameMasterAndPoWithDifferentPackingPlanIsAllowed() {
        Map<AllBpPoFourKeyGuard.Key, Integer> seen = new HashMap<>();
        AllBpPoFourKeyGuard.checkFileRow(seen, "MP01", "PO01", "PLAN1", "SO01", 3);
        AllBpPoFourKeyGuard.checkFileRow(seen, "MP01", "PO01", "PLAN2", "SO01", 4);
        assertEquals(2, seen.size());
    }

    @Test
    void sameMasterAndPoAndPlanWithDifferentSoPtsIsAllowed() {
        Map<AllBpPoFourKeyGuard.Key, Integer> seen = new HashMap<>();
        AllBpPoFourKeyGuard.checkFileRow(seen, "MP01", "PO01", "PLAN1", "SO01", 3);
        AllBpPoFourKeyGuard.checkFileRow(seen, "MP01", "PO01", "PLAN1", "SO02", 4);
        assertEquals(2, seen.size());
    }

    @Test
    void fileRejectsSameFourFieldsRegardlessOfCaseAndWhitespace() {
        Map<AllBpPoFourKeyGuard.Key, Integer> seen = new HashMap<>();
        AllBpPoFourKeyGuard.checkFileRow(seen, "Master 01", "PO01", "P 1", "SO01", 3);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AllBpPoFourKeyGuard.checkFileRow(seen, " master  01 ", " po01 ", "p   1", "so01", 10));
        assertTrue(error.getMessage().contains("Excel rows 3 and 10"));
        assertTrue(error.getMessage().contains("Packing Plan '"));
        assertTrue(error.getMessage().contains("SO (PTS) '"));
        assertTrue(error.getMessage().contains("Upload rejected"));
    }

    @Test
    void rejectsMatchingExistingRecordInSameOrder() {
        PurchaseOrder old = po("old", "MP01", "PO01", "PLAN01", "SO01");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AllBpPoFourKeyGuard.checkAgainstSaved(List.of(old),
                        Set.of(AllBpPoFourKeyGuard.key("mp01", "po01", "plan01", "so01"))));
        assertTrue(error.getMessage().contains("already exists in this Order"));
    }

    @Test
    void allowsSavedSamePairWhenPlanOrSoDiffer() {
        PurchaseOrder old = po("old", "MP01", "PO01", "PLAN01", "SO01");
        assertDoesNotThrow(() -> AllBpPoFourKeyGuard.checkAgainstSaved(List.of(old),
                Set.of(AllBpPoFourKeyGuard.key("MP01", "PO01", "PLAN02", "SO01"),
                        AllBpPoFourKeyGuard.key("MP01", "PO01", "PLAN01", "SO02"))));
    }

    @Test
    void editingBlocksCollisionButAllowsSelfAndDifferentPlan() {
        PurchaseOrder old = po("p1", "MP01", "PO01", "PLAN01", "SO01");
        assertDoesNotThrow(() -> AllBpPoFourKeyGuard.checkEditAgainstSaved(
                List.of(old), "p1", "MP01", "PO01", "PLAN01", "SO01"));
        assertDoesNotThrow(() -> AllBpPoFourKeyGuard.checkEditAgainstSaved(
                List.of(old), "p2", "MP01", "PO01", "PLAN02", "SO01"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AllBpPoFourKeyGuard.checkEditAgainstSaved(
                        List.of(old), "p2", "MP01", "PO01", "PLAN01", "SO01"));
        assertTrue(error.getMessage().contains("Cannot save purchase order"));
    }

    @Test
    void blankPlanAndSoCountAsSameKeyWhenOtherFieldsMatch() {
        Map<AllBpPoFourKeyGuard.Key, Integer> seen = new HashMap<>();
        AllBpPoFourKeyGuard.checkFileRow(seen, "MP01", "PO01", null, " ", 2);
        assertThrows(IllegalArgumentException.class,
                () -> AllBpPoFourKeyGuard.checkFileRow(seen, "MP01", "PO01", "", null, 5));
    }
}
