package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.*;
import org.bsl.cartonloading.buyers.lululemon.repository.*;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShippingScheduleQcReportServiceTest {
    private final CartonRepository cartons = mock(CartonRepository.class);
    private final CartonItemRepository items = mock(CartonItemRepository.class);
    private final RfidScanSessionRepository sessions = mock(RfidScanSessionRepository.class);
    private final PurchaseOrderRepository pos = mock(PurchaseOrderRepository.class);
    private final ShippingScheduleQcReportService service = new ShippingScheduleQcReportService(cartons, items, sessions, pos);

    @Test void countsUngeneratedCartonsAndPreservesFailedRetriesOnScanDate() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId("po1"); po.setOrderId("order1"); po.setBuyerCode(AppConstants.Buyer.LULULEMON);
        po.setFactoryCode("F3"); po.setPoNumber("20627092"); po.setPlannedCartons(3);
        po.setCartonPlannedQuantities(List.of(2, 2, 2));
        Carton carton = new Carton();
        carton.setId("carton1"); carton.setPoId("po1"); carton.setOrderId("order1");
        carton.setBuyerCode(AppConstants.Buyer.LULULEMON); carton.setFactoryCode("F3");
        carton.setCartonNo(1); carton.setPlannedQty(2); carton.setSscc18("123456789012345678");
        carton.setLabelConfirmedSku("123456"); carton.setRfidStatus("PASS"); carton.setWeightStatus("PASS");
        CartonItem item1 = new CartonItem(); item1.setCartonId("carton1"); item1.setOrderId("order1");
        item1.setBuyerCode(AppConstants.Buyer.LULULEMON); item1.setExpectedSku("123456");
        CartonItem item2 = new CartonItem(); item2.setCartonId("carton1"); item2.setOrderId("order1");
        item2.setBuyerCode(AppConstants.Buyer.LULULEMON); item2.setExpectedSku("123456");
        RfidScanSession failed = new RfidScanSession();
        failed.setId("scan1"); failed.setCartonId("carton1"); failed.setOrderId("order1");
        failed.setPoId("po1"); failed.setBuyerCode(AppConstants.Buyer.LULULEMON);
        failed.setStatus("FAILED"); failed.setExpectedQty(2); failed.setActualQty(1);
        failed.setFinishedAt(LocalDateTime.of(2026, 11, 14, 9, 0));
        RfidScanSession passed = new RfidScanSession();
        passed.setId("scan2"); passed.setCartonId("carton1"); passed.setOrderId("order1");
        passed.setPoId("po1"); passed.setBuyerCode(AppConstants.Buyer.LULULEMON);
        passed.setStatus("PASS"); passed.setExpectedQty(2); passed.setActualQty(2);
        passed.setFinishedAt(LocalDateTime.of(2026, 11, 14, 10, 0));
        when(pos.findByOrderIdAndBuyerCodeOrderByPoNumberAsc("order1", AppConstants.Buyer.LULULEMON)).thenReturn(List.of(po));
        when(cartons.findByPoIdIn(List.of("po1"))).thenReturn(List.of(carton));
        when(items.findByPoIdOrderByCartonNoAscItemNoAsc("po1")).thenReturn(List.of(item1, item2));
        when(sessions.findByBuyerCodeAndCartonIdInOrderByStartedAtDesc(AppConstants.Buyer.LULULEMON, List.of("carton1")))
                .thenReturn(List.of(passed, failed));
        var day = new PoShippingPlanService.ShippingDayDetail("2026-11-15", "F3", List.of(
                new PoShippingPlanService.ShippingPo("po1", "20627092", null, null, null, null, 6, 3, null, null)),
                false, false, false, null, null);
        var result = service.report(day, "order1", LocalDate.of(2026, 11, 14));
        var summary = result.pos().get(0);
        assertEquals(3, summary.plannedCartons());
        assertEquals(1, summary.generatedCartons());
        assertEquals(1, summary.ssccAssigned());
        assertEquals(1, summary.skuAssigned()); // one fully assigned carton
        assertEquals(6, summary.plannedItems()); // 3 cartons x 2 products
        assertEquals(2, summary.assignedItems()); // actual item slots with assigned SKU
        assertEquals(1, summary.completedCartons());
        assertEquals(1, summary.rfidPass());
        assertEquals(0, summary.rfidFailed());
        assertEquals(2, summary.rfidNotScanned());
        assertEquals(1, summary.scanDayPassAttempts());
        assertEquals(1, summary.scanDayFailedAttempts());
        assertEquals(1, summary.scanDayCartons());
        assertEquals(3, result.cartons().size());
        assertFalse(result.cartons().get(1).generated());
        assertTrue(ShippingScheduleQcReportService.matches(summary, "", "MISSING", "", "", null));
    }
    @Test void itemQuantityIsProductsInsideCartonsNotTheCartonCount() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId("poQ"); po.setOrderId("orderQ"); po.setBuyerCode(AppConstants.Buyer.LULULEMON);
        po.setFactoryCode("F3"); po.setPoNumber("20627092");
        po.setPlannedCartons(2); po.setCartonPlannedQuantities(List.of(30, 12));
        Carton carton = new Carton();
        carton.setId("cQ"); carton.setPoId("poQ"); carton.setOrderId("orderQ");
        carton.setBuyerCode(AppConstants.Buyer.LULULEMON); carton.setFactoryCode("F3");
        carton.setCartonNo(1); carton.setPlannedQty(30); carton.setLabelConfirmedSku("SKU1");
        CartonItem assigned = new CartonItem();
        assigned.setCartonId("cQ"); assigned.setOrderId("orderQ");
        assigned.setBuyerCode(AppConstants.Buyer.LULULEMON); assigned.setExpectedSku("SKU1");
        CartonItem unassigned = new CartonItem();
        unassigned.setCartonId("cQ"); unassigned.setOrderId("orderQ");
        unassigned.setBuyerCode(AppConstants.Buyer.LULULEMON);
        when(pos.findByOrderIdAndBuyerCodeOrderByPoNumberAsc("orderQ", AppConstants.Buyer.LULULEMON))
                .thenReturn(List.of(po));
        when(cartons.findByPoIdIn(List.of("poQ"))).thenReturn(List.of(carton));
        when(items.findByPoIdOrderByCartonNoAscItemNoAsc("poQ")).thenReturn(List.of(assigned, unassigned));
        when(sessions.findByBuyerCodeAndCartonIdInOrderByStartedAtDesc(AppConstants.Buyer.LULULEMON, List.of("cQ")))
                .thenReturn(List.of());
        var day = new PoShippingPlanService.ShippingDayDetail("2026-11-15", "F3", List.of(
                new PoShippingPlanService.ShippingPo("poQ", "20627092", null, null, null, null, 42, 2, null, null)),
                false, false, false, null, null);
        var summary = service.report(day, "orderQ", null).pos().get(0);
        assertEquals(2, summary.plannedCartons());
        assertEquals(42, summary.plannedItems());
        assertEquals(1, summary.assignedItems());
        assertEquals(0, summary.skuAssigned());
        assertTrue(ShippingScheduleQcReportService.skuItemsAssignedCell(summary).equals("1 / 42"));
    }

    @Test void exportSeparatesTwoOptInAssignmentColumnsAndKeepsPoIdOrder() throws Exception {
        // PO numbers can repeat for different packing plans: align by membership PO ID, not the displayed PO number.
        var first = new ShippingScheduleQcReportService.PoQc("poA", "20627092", "F3",
                67, 60, 55, 60, 58, 1992, 1700, 50, 5, 12, 0, 2, 51, 6, 3, 8);
        var hidden = new ShippingScheduleQcReportService.PoQc("poB", "20627092", "F3",
                80, 80, 50, 70, 70, 2400, 2100, 60, 10, 10, 0, 5, 55, 0, 0, 0);
        var last = new ShippingScheduleQcReportService.PoQc("poC", "20627092", "F3",
                128, 128, 120, 125, 124, 3816, 3744, 115, 8, 5, 0, 3, 116, 9, 2, 10);
        var report = new ShippingScheduleQcReportService.Report("2026-11-15", "F3", "2026-11-14",
                List.of(first, hidden, last), List.of(), List.of());
        byte[] original;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Shipping POs");
            sheet.createRow(0).createCell(0).setCellValue("Shipping Date 2026-11-15 | Factory F3");
            sheet.createRow(2).createCell(0).setCellValue("PO");
            sheet.getRow(2).createCell(1).setCellValue("Ship Mode");
            sheet.createRow(3).createCell(0).setCellValue("20627092");
            sheet.createRow(4).createCell(0).setCellValue("20627092");
            workbook.write(stream);
            original = stream.toByteArray();
        }
        byte[] output = service.appendExcel(original, report, Set.of("poA", "poC"),
                LocalDate.of(2026, 11, 14), true, true, true);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(output))) {
            assertEquals(1, workbook.getNumberOfSheets());
            Sheet sheet = workbook.getSheet("Shipping POs");
            assertEquals(6, sheet.getRow(2).getLastCellNum());
            assertEquals("Carton Completed", sheet.getRow(2).getCell(2).getStringCellValue());
            assertEquals("SSCC Assigned", sheet.getRow(2).getCell(3).getStringCellValue());
            assertEquals("SKU Items Assigned", sheet.getRow(2).getCell(4).getStringCellValue());
            assertEquals("RFID Scan Result", sheet.getRow(2).getCell(5).getStringCellValue());
            assertEquals("55 / 67", sheet.getRow(3).getCell(2).getStringCellValue());
            assertEquals("120 / 128", sheet.getRow(4).getCell(2).getStringCellValue());
            assertEquals("60 / 67", sheet.getRow(3).getCell(3).getStringCellValue());
            assertEquals("125 / 128", sheet.getRow(4).getCell(3).getStringCellValue());
            assertEquals("1700 / 1992", sheet.getRow(3).getCell(4).getStringCellValue());
            assertEquals("3744 / 3816", sheet.getRow(4).getCell(4).getStringCellValue());
            assertTrue(sheet.getRow(3).getCell(5).getStringCellValue().contains("NOT SCANNED: 12"));
            assertTrue(sheet.getRow(3).getCell(5).getStringCellValue().contains("2026-11-14 scan attempts"));
            assertNull(workbook.getSheet("QC Summary"));
            assertNull(workbook.getSheet("Carton Details"));
        }
    }

    @Test void optionalAssignmentColumnsAreOffByDefaultAndIndependentlySelectable() throws Exception {
        var summary = new ShippingScheduleQcReportService.PoQc("poA", "20627092", "F3",
                67, 4, 3, 1, 1, 1992, 30, 1, 0, 66, 0, 0, 1, 0, 0, 0);
        var report = new ShippingScheduleQcReportService.Report("2026-11-15", "F3", null,
                List.of(summary), List.of(), List.of());
        byte[] original;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Shipping POs");
            sheet.createRow(0).createCell(0).setCellValue("Shipping Date 2026-11-15 | Factory F3");
            sheet.createRow(2).createCell(0).setCellValue("PO");
            sheet.createRow(3).createCell(0).setCellValue("20627092");
            wb.write(stream);
            original = stream.toByteArray();
        }
        // When optional columns are unchecked, only the original QC summary remains.
        byte[] defaultOutput = service.appendExcel(original, report, Set.of("poA"),
                null, true, false, false);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(defaultOutput))) {
            Sheet s = wb.getSheet("Shipping POs");
            assertEquals(3, s.getRow(2).getLastCellNum());
            assertEquals("Carton Completed", s.getRow(2).getCell(1).getStringCellValue());
            assertEquals("RFID Scan Result", s.getRow(2).getCell(2).getStringCellValue());
        }
        byte[] ssccOnly = service.appendExcel(original, report, Set.of("poA"),
                null, false, true, false);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(ssccOnly))) {
            Sheet s = wb.getSheet("Shipping POs");
            assertEquals(2, s.getRow(2).getLastCellNum());
            assertEquals("SSCC Assigned", s.getRow(2).getCell(1).getStringCellValue());
            assertEquals("1 / 67", s.getRow(3).getCell(1).getStringCellValue());
        }
        byte[] skuOnly = service.appendExcel(original, report, Set.of("poA"),
                null, false, false, true);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(skuOnly))) {
            Sheet s = wb.getSheet("Shipping POs");
            assertEquals(2, s.getRow(2).getLastCellNum());
            assertEquals("SKU Items Assigned", s.getRow(2).getCell(1).getStringCellValue());
            assertEquals("30 / 1992", s.getRow(3).getCell(1).getStringCellValue());
        }
    }

}
