package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.shared.orders.model.OrderDocument;
import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Report status is deliberately different from an in-progress carton warning. */
class ManagementReportServiceTest {
    private static Carton carton(String rfid, String weight) {
        Carton value = new Carton();
        value.setRfidStatus(rfid);
        value.setWeightStatus(weight);
        return value;
    }

    @Test void pendingWhenRfidFailedButWeightNotChecked() {
        assertEquals("PENDING", ManagementReportService.cartonFinalStatus(carton("FAILED", null)));
    }

    @Test void pendingWhenWeightFailedButRfidNotChecked() {
        assertEquals("PENDING", ManagementReportService.cartonFinalStatus(carton(null, "FAILED")));
    }

    @Test void failedOnlyAfterBothInspectionsHaveFinished() {
        assertEquals("FAILED", ManagementReportService.cartonFinalStatus(carton("FAILED", "PASS")));
        assertEquals("FAILED", ManagementReportService.cartonFinalStatus(carton("PASS", "FAILED")));
        assertEquals("FAILED", ManagementReportService.cartonFinalStatus(carton("FAILED", "FAILED")));
    }

    @Test void finalPassNeedsBothChecksPassed() {
        assertEquals("PASS", ManagementReportService.cartonFinalStatus(carton("PASS", "PASS")));
        assertEquals("PENDING", ManagementReportService.cartonFinalStatus(carton("PASS", "PENDING")));
    }
    @Test void exportCreatesReadableWorkbookFor75PoAssignmentsAndSanitizesImportedText() throws Exception {
        MongoTemplate mongo = mock(MongoTemplate.class);
        BuyerFactoryAccessService access = mock(BuyerFactoryAccessService.class);
        User actor = mock(User.class);
        when(access.currentUser()).thenReturn(actor);
        when(actor.isEnabled()).thenReturn(true);
        when(actor.canLululemonViewManagementReports()).thenReturn(true);
        when(actor.canLululemonReportAllFactories()).thenReturn(true);
        when(actor.canLululemonExportManagementReports()).thenReturn(true);
        when(access.isUnrestricted()).thenReturn(true);
        when(access.canAccessAssignedFactory("F3")).thenReturn(true);

        java.util.List<PurchaseOrder> pos = new java.util.ArrayList<>();
        for (int i = 0; i < 75; i++) {
            PurchaseOrder po = new PurchaseOrder();
            po.setId("po-" + i);
            po.setBuyerCode(AppConstants.Buyer.LULULEMON);
            po.setOrderId("order1");
            po.setFactoryCode("F3");
            po.setPoNumber("PO-" + i);
            po.setPlannedCartons(10);
            po.setShippingDates(List.of(LocalDate.of(2026, 11, 12)));
            if (i == 0) po.setStyle("Imported" + (char) 1 + "bad " + "A".repeat(33000));
            pos.add(po);
        }
        when(mongo.find(any(Query.class), eq(PurchaseOrder.class))).thenReturn(pos);
        OrderDocument order = new OrderDocument();
        order.setId("order1"); order.setBuyerCode(AppConstants.Buyer.LULULEMON);
        order.setOrderName("Order AA");
        when(mongo.find(any(Query.class), eq(OrderDocument.class))).thenReturn(List.of(order));
        when(mongo.find(any(Query.class), eq(Carton.class))).thenReturn(List.of());
        when(mongo.find(any(Query.class), eq(Document.class), eq("po_shipping_day_submissions")))
                .thenReturn(List.of());

        ManagementReportService service = new ManagementReportService(mongo, access);
        var filters = new ManagementReportService.Filters(LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 16), "F3", "", "", "");
        byte[] bytes = service.export(filters);
        assertTrue(bytes.length > 0);
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(1, book.getNumberOfSheets());
            var sheet = book.getSheet("Shipping & Order Report");
            assertNotNull(sheet);
            assertEquals(78, sheet.getPhysicalNumberOfRows());
            assertEquals("Shipping Date", sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals(21, sheet.getRow(2).getPhysicalNumberOfCells());
            assertEquals("Planned CTN", sheet.getRow(2).getCell(12).getStringCellValue());
            assertEquals("RFID PASS", sheet.getRow(2).getCell(13).getStringCellValue());
            assertEquals("Status", sheet.getRow(2).getCell(20).getStringCellValue());
            String style = sheet.getRow(3).getCell(6).getStringCellValue();
            assertFalse(style.contains(String.valueOf((char) 1)));
            assertTrue(style.length() <= 32767);
            assertNull(book.getSheet("Carton Details"));
        }
    }

    @Test void exportPoCreatesOnlyCartonDetailsAndRespectsSelectedPo() throws Exception {
        MongoTemplate mongo = mock(MongoTemplate.class);
        BuyerFactoryAccessService access = mock(BuyerFactoryAccessService.class);
        User actor = mock(User.class);
        when(access.currentUser()).thenReturn(actor);
        when(actor.isEnabled()).thenReturn(true);
        when(actor.canLululemonViewManagementReports()).thenReturn(true);
        when(actor.canLululemonReportAllFactories()).thenReturn(true);
        when(actor.canLululemonExportManagementReports()).thenReturn(true);
        when(access.isUnrestricted()).thenReturn(true);
        when(access.canAccessAssignedFactory("F3")).thenReturn(true);

        PurchaseOrder po = new PurchaseOrder();
        po.setId("p1"); po.setBuyerCode(AppConstants.Buyer.LULULEMON);
        po.setOrderId("order1"); po.setFactoryCode("F3"); po.setPoNumber("PO1002");
        po.setPlannedCartons(2); po.setShippingDates(List.of(LocalDate.of(2026, 11, 12)));
        when(mongo.find(any(Query.class), eq(PurchaseOrder.class))).thenReturn(List.of(po));
        OrderDocument order = new OrderDocument(); order.setId("order1");
        order.setBuyerCode(AppConstants.Buyer.LULULEMON); order.setOrderName("Order AA");
        when(mongo.find(any(Query.class), eq(OrderDocument.class))).thenReturn(List.of(order));
        Carton c = carton("PASS", "PASS");
        c.setId("carton1"); c.setOrderId("order1"); c.setPoId("p1");
        c.setBuyerCode(AppConstants.Buyer.LULULEMON); c.setFactoryCode("F3");
        c.setCartonNo(1); c.setSscc18("123456789012345678");
        when(mongo.find(any(Query.class), eq(Carton.class))).thenReturn(List.of(c));
        when(mongo.find(any(Query.class), eq(Document.class), eq("po_shipping_day_submissions")))
                .thenReturn(List.of());

        ManagementReportService service = new ManagementReportService(mongo, access);
        var filters = new ManagementReportService.Filters(LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 16), "F3", "2026-11-12", "", "");
        byte[] bytes = service.exportPo(filters, "p1", "2026-11-12");
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(1, book.getNumberOfSheets());
            var sheet = book.getSheet("Carton Details");
            assertNotNull(sheet);
            assertEquals(4, sheet.getPhysicalNumberOfRows());
            assertEquals("SSCC-18", sheet.getRow(2).getCell(6).getStringCellValue());
            assertEquals("123456789012345678", sheet.getRow(3).getCell(6).getStringCellValue());
            assertEquals("PASS", sheet.getRow(3).getCell(11).getStringCellValue());
            assertNull(book.getSheet("Shipping & Order Report"));
        }
    }

    @Test void rangeFactoryScopeAndPoDetailsDoNotLeakOtherFactory() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        BuyerFactoryAccessService access = mock(BuyerFactoryAccessService.class);
        User actor = mock(User.class);
        when(access.currentUser()).thenReturn(actor);
        when(actor.isEnabled()).thenReturn(true);
        when(actor.canLululemonViewManagementReports()).thenReturn(true);
        when(actor.canLululemonReportAllFactories()).thenReturn(false);
        when(actor.getFactoryPermissions()).thenReturn(List.of("F1"));

        PurchaseOrder f1 = new PurchaseOrder();
        f1.setId("p1"); f1.setBuyerCode(AppConstants.Buyer.LULULEMON); f1.setOrderId("order1");
        f1.setFactoryCode("F1"); f1.setPoNumber("PO123"); f1.setPlannedCartons(3);
        f1.setShippingDates(List.of(LocalDate.of(2026, 11, 5)));
        PurchaseOrder f2 = new PurchaseOrder();
        f2.setId("p2"); f2.setBuyerCode(AppConstants.Buyer.LULULEMON); f2.setOrderId("order2");
        f2.setFactoryCode("F2"); f2.setPoNumber("SECRET"); f2.setPlannedCartons(7);
        f2.setShippingDates(List.of(LocalDate.of(2026, 11, 5)));
        PurchaseOrder outOfRange = new PurchaseOrder();
        outOfRange.setId("p3"); outOfRange.setBuyerCode(AppConstants.Buyer.LULULEMON);
        outOfRange.setOrderId("order1"); outOfRange.setFactoryCode("F1");
        outOfRange.setPoNumber("LATER"); outOfRange.setPlannedShippingDate(LocalDate.of(2026, 12, 1));
        when(mongo.find(any(Query.class), eq(PurchaseOrder.class)))
                .thenReturn(List.of(f1, f2, outOfRange));
        OrderDocument order = new OrderDocument(); order.setId("order1");
        order.setBuyerCode(AppConstants.Buyer.LULULEMON); order.setOrderName("November Export");
        when(mongo.find(any(Query.class), eq(OrderDocument.class))).thenReturn(List.of(order));
        Carton inspected = carton("FAILED", "PASS");
        inspected.setId("carton1"); inspected.setPoId("p1"); inspected.setOrderId("order1");
        inspected.setBuyerCode(AppConstants.Buyer.LULULEMON); inspected.setFactoryCode("F1");
        inspected.setCartonNo(1);
        when(mongo.find(any(Query.class), eq(Carton.class))).thenReturn(List.of(inspected));
        when(mongo.find(any(Query.class), eq(Document.class), eq("po_shipping_day_submissions")))
                .thenReturn(List.of());

        ManagementReportService service = new ManagementReportService(mongo, access);
        var filters = new ManagementReportService.Filters(LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 16), "", "", "", "");
        var result = service.report(filters, 0, 25);
        assertEquals(1, result.totalRows());
        assertEquals("PO123", result.pos().get(0).poNumber());
        assertEquals(1, result.totals().finalFailed());
        assertEquals(2, result.totals().pending());
        assertEquals("F1", result.factories().get(0).factoryCode());
        assertEquals(1, result.totals().uniquePos());
        assertEquals("November Export", result.shippingDays().get(0).orderName());
        var detail = service.poDetail(filters, "p1", "2026-11-05", 0, 10);
        assertEquals(1, detail.totalCartons());
        assertEquals("FAILED", detail.cartons().get(0).finalStatus());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.poDetail(filters, "p2", "2026-11-05", 0, 10));
    }

}
