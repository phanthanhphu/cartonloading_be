package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PoShippingExportColumnsTest {
    @Test
    void defaultsAreTheTwelveApprovedColumnsInOrder() {
        assertEquals(List.of("DC_CODE", "CHANNEL", "PO", "STYLE", "DESCRIPTION", "COLOR", "QTY",
                "PCS_CTN", "CTNS", "REMAINDER", "REMARK", "SHIP_MODE"),
                PoShippingExportColumns.resolve(null));
        assertEquals("Color Description", PoShippingExportColumns.label("COLOR"));
    }

    @Test
    void customColumnsPreserveOrderAndRejectInvalidValues() {
        assertEquals(List.of("PO", "SHIP_MODE", "DC_CODE"),
                PoShippingExportColumns.resolve(List.of("PO", "SHIP_MODE", "DC_CODE")));
        assertThrows(IllegalArgumentException.class, () -> PoShippingExportColumns.resolve(List.of()));
        assertThrows(IllegalArgumentException.class, () -> PoShippingExportColumns.resolve(List.of("PO", "PO")));
        assertThrows(IllegalArgumentException.class, () -> PoShippingExportColumns.resolve(List.of("PASSWORD")));
    }

    @Test
    void cartonsAndRemainderFollowAllPosPerSourceRowMathAndNeverFabricateRemark() {
        PurchaseOrder po = new PurchaseOrder();
        po.setPoNumber("20307753");
        po.setPlannedTotalQty(25);
        po.setPcsPerCarton(14);
        po.setAllBpRows(List.of(
                Map.of("Q'ty", "15", "Pcs/ctn", "14", "Remark", "Partial A", "Chanel", "Retail"),
                Map.of("Q'ty", "10", "Pcs/ctn", "6", "Remark", "Partial B", "Chanel", "Retail")));
        assertEquals(2L, ((Number) PoShippingExportColumns.cell("CTNS", po, LocalDate.of(2026, 11, 15), "ABC", 1)).longValue());
        assertEquals(5L, ((Number) PoShippingExportColumns.cell("REMAINDER", po, LocalDate.of(2026, 11, 15), "ABC", 1)).longValue());
        assertEquals("Partial A | Partial B", PoShippingExportColumns.cell("REMARK", po, LocalDate.of(2026, 11, 15), "ABC", 1));
    }

    @Test
    void absentOptionalSourceFieldIsBlank() {
        PurchaseOrder po = new PurchaseOrder();
        assertEquals("", PoShippingExportColumns.cell("REMARK", po, LocalDate.of(2026, 11, 15), "ABC", 1));
        assertNull(PoShippingExportColumns.cell("CTNS", po, LocalDate.of(2026, 11, 15), "ABC", 1));
    }
}
