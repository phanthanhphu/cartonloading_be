package org.bsl.cartonloading.buyers.es.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.constants.AppMessages;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.bsl.cartonloading.buyers.es.model.PackingAllocationLine;
import org.bsl.cartonloading.buyers.es.model.PackingListLine;
import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.bsl.cartonloading.buyers.es.repository.PackingAllocationLineRepository;
import org.bsl.cartonloading.buyers.es.repository.PackingListLineRepository;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Service
public class OrderPackingExcelExportService {
    private final PackingOrderService orderService;
    private final PackingAllocationLineRepository masterRepository;
    private final PackingListLineRepository packingRepository;

    public OrderPackingExcelExportService(
            PackingOrderService orderService,
            PackingAllocationLineRepository masterRepository,
            PackingListLineRepository packingRepository
    ) {
        this.orderService = orderService;
        this.masterRepository = masterRepository;
        this.packingRepository = packingRepository;
    }

    public byte[] exportMaster(String buyerCode, String orderId) {
        PackingOrder order = orderService.getEntity(buyerCode, orderId);
        List<PackingAllocationLine> lines = masterRepository.findByOrderIdAndBuyerCode(order.getId(), order.getBuyerCode()).stream()
                .sorted(Comparator.comparing(PackingAllocationLine::getLineNo, Comparator.nullsLast(Integer::compareTo)))
                .toList();

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(AppConstants.Excel.ALLOCATION_SHEET);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle dateStyle = dateStyle(workbook);
            Row header = sheet.createRow(0);
            for (int i = 0; i < AppConstants.ExcelExport.MASTER_HEADERS.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(AppConstants.ExcelExport.MASTER_HEADERS.get(i));
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 1;
            for (PackingAllocationLine line : lines) {
                Row row = sheet.createRow(rowIndex++);
                int c = 0;
                text(row, c++, AppConstants.ExcelAction.UPDATE);
                text(row, c++, line.getId());
                text(row, c++, line.getSupplierName());
                text(row, c++, line.getSupplierNumber());
                text(row, c++, line.getProductionFacility());
                text(row, c++, line.getContainerNumber());
                text(row, c++, line.getShipmentMode());
                date(row, c++, line.getEtd(), dateStyle);
                date(row, c++, line.getEta(), dateStyle);
                text(row, c++, line.getPoNumber());
                text(row, c++, line.getArticleNumber());
                text(row, c++, line.getStyleNumber());
                text(row, c++, line.getStyle());
                text(row, c++, line.getColor());
                text(row, c++, line.getSize());
                number(row, c++, line.getQtyPerCarton());
                text(row, c++, line.getInvoiceNumber());
                number(row, c++, line.getTotalPcs());
                number(row, c++, line.getTotalCartons());
                number(row, c++, line.getPcsAir());
                number(row, c++, line.getCartonsAir());
                number(row, c++, line.getPcsSea());
                number(row, c++, line.getCartonsSea());
                number(row, c++, line.getCbmAir());
                number(row, c++, line.getKgAir());
                text(row, c++, line.getStatus());
                number(row, c++, line.getOpenPoQtyOverdel());
                text(row, c++, line.getRemarks());
                text(row, c++, line.getYoLotNumber());
                number(row, c++, line.getHeightCarton());
                number(row, c, line.getCbmCtn());
            }

            for (int i = 0; i < 20; i++) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(AppConstants.ExcelAction.CREATE);
            }
            addActionValidation(sheet, Math.max(5000, rowIndex + 100));
            sheet.createFreezePane(2, 1);
            setMasterWidths(sheet);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, rowIndex - 1), 0, AppConstants.ExcelExport.MASTER_HEADERS.size() - 1));
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(AppMessages.cannotExport(AppConstants.ExportSubject.ORDER_ITEMS, ex.getMessage()), ex);
        }
    }

    public byte[] exportPackingList(String buyerCode, String orderId) {
        PackingOrder order = orderService.getEntity(buyerCode, orderId);
        List<PackingListLine> lines = packingRepository.findByOrderIdAndBuyerCode(order.getId(), order.getBuyerCode()).stream()
                .sorted(Comparator.comparing(PackingListLine::getLineNo, Comparator.nullsLast(Integer::compareTo)))
                .toList();

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(AppConstants.Excel.PACKING_LIST_SHEET);
            CellStyle titleStyle = titleStyle(workbook);
            CellStyle labelStyle = labelStyle(workbook);
            CellStyle headerStyle = headerStyle(workbook);

            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, AppConstants.ExcelExport.PACKING_HEADERS.size() - 1));
            Cell title = sheet.createRow(0).createCell(0);
            title.setCellValue(AppConstants.Excel.PACKING_LIST_SHEET);
            title.setCellStyle(titleStyle);

            Row info1 = sheet.createRow(2);
            info1.createCell(0).setCellValue("Buyer");
            info1.getCell(0).setCellStyle(labelStyle);
            info1.createCell(1).setCellValue(order.getBuyerCode());
            info1.createCell(3).setCellValue("Date");
            info1.getCell(3).setCellStyle(labelStyle);
            info1.createCell(4).setCellValue(order.getOrderDate() == null ? "" : order.getOrderDate().toString());
            info1.createCell(6).setCellValue("Order Name");
            info1.getCell(6).setCellStyle(labelStyle);
            info1.createCell(7).setCellValue(safe(order.getOrderName()));

            Row info2 = sheet.createRow(3);
            info2.createCell(0).setCellValue("Supplier");
            info2.getCell(0).setCellStyle(labelStyle);
            info2.createCell(1).setCellValue(safe(order.getSupplierName()));
            info2.createCell(3).setCellValue("e.s. Supplier #");
            info2.getCell(3).setCellStyle(labelStyle);
            info2.createCell(4).setCellValue(safe(order.getSupplierNumber()));
            info2.createCell(6).setCellValue("Production Facility");
            info2.getCell(6).setCellStyle(labelStyle);
            info2.createCell(7).setCellValue(safe(order.getProductionFacility()));

            Row header = sheet.createRow(5);
            for (int i = 0; i < AppConstants.ExcelExport.PACKING_HEADERS.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(AppConstants.ExcelExport.PACKING_HEADERS.get(i));
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 6;
            for (PackingListLine line : lines) {
                Row row = sheet.createRow(rowIndex++);
                int c = 0;
                number(row, c++, line.getCartonFrom());
                number(row, c++, line.getCartonTo());
                number(row, c++, line.getCartonsQty());
                text(row, c++, line.getPoNumber());
                text(row, c++, line.getStyleNumber());
                text(row, c++, line.getStyle());
                text(row, c++, line.getArticleNumber());
                text(row, c++, line.getColor());
                text(row, c++, line.getSize());
                number(row, c++, line.getQtyPerCarton());
                number(row, c++, line.getTotalPcs());
                text(row, c++, line.getCartonMeasurement());
                number(row, c++, line.getCbm());
                number(row, c++, line.getGrossWeightKg());
                number(row, c++, line.getNetWeightKg());
                number(row, c++, line.getActualWeightKg());
                text(row, c, line.getRemarks());
            }

            int summaryRowIndex = rowIndex + 1;
            Row summary = sheet.createRow(summaryRowIndex);
            summary.createCell(0).setCellValue("SUMMARY");
            summary.getCell(0).setCellStyle(labelStyle);
            summary.createCell(2).setCellValue("Total Cartons");
            summary.getCell(2).setCellStyle(labelStyle);
            summary.createCell(3).setCellFormula(lines.isEmpty() ? "0" : "SUM(C7:C" + rowIndex + ")");
            summary.createCell(5).setCellValue("Total PCS");
            summary.getCell(5).setCellStyle(labelStyle);
            summary.createCell(6).setCellFormula(lines.isEmpty() ? "0" : "SUM(K7:K" + rowIndex + ")");
            summary.createCell(8).setCellValue("Total CBM");
            summary.getCell(8).setCellStyle(labelStyle);
            summary.createCell(9).setCellFormula(lines.isEmpty() ? "0" : "SUM(M7:M" + rowIndex + ")");
            summary.createCell(11).setCellValue("Total Gross Weight");
            summary.getCell(11).setCellStyle(labelStyle);
            summary.createCell(12).setCellFormula(lines.isEmpty() ? "0" : "SUM(N7:N" + rowIndex + ")");

            sheet.createFreezePane(0, 6);
            setPackingWidths(sheet);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(5, Math.max(5, rowIndex - 1), 0, AppConstants.ExcelExport.PACKING_HEADERS.size() - 1));
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(AppMessages.cannotExport(AppConstants.ExportSubject.PACKING_LIST, ex.getMessage()), ex);
        }
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.DARK_TEAL.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private CellStyle titleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setAlignment(HorizontalAlignment.CENTER);
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 18);
        style.setFont(font);
        return style;
    }

    private CellStyle labelStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private CellStyle dateStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
        return style;
    }

    private void addActionValidation(Sheet sheet, int lastRow) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createExplicitListConstraint(new String[]{AppConstants.ExcelAction.CREATE, AppConstants.ExcelAction.UPDATE, AppConstants.ExcelAction.DELETE});
        DataValidation validation = helper.createValidation(constraint, new CellRangeAddressList(1, lastRow, 0, 0));
        validation.setShowErrorBox(true);
        validation.createErrorBox(AppMessages.INVALID_ACTION_TITLE, AppMessages.INVALID_ACTION_HELP);
        validation.createPromptBox("ACTION", "CREATE = add, UPDATE = edit by KEY, DELETE = remove by KEY.");
        validation.setShowPromptBox(true);
        sheet.addValidationData(validation);
    }

    private void text(Row row, int column, String value) {
        row.createCell(column).setCellValue(safe(value));
    }

    private void number(Row row, int column, BigDecimal value) {
        if (value == null) row.createCell(column);
        else row.createCell(column).setCellValue(value.doubleValue());
    }

    private void date(Row row, int column, LocalDate value, CellStyle style) {
        Cell cell = row.createCell(column);
        if (value != null) {
            cell.setCellValue(value);
            cell.setCellStyle(style);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void setMasterWidths(Sheet sheet) {
        int[] widths = {12,26,18,14,18,16,17,12,12,14,15,14,38,18,11,13,15,13,13,12,12,12,12,12,12,12,22,24,12,10,12};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
        sheet.getRow(0).setHeightInPoints(34);
    }

    private void setPackingWidths(Sheet sheet) {
        int[] widths = {11,11,11,14,14,38,15,18,12,11,12,16,12,18,18,19,24};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
        sheet.getRow(0).setHeightInPoints(28);
        sheet.getRow(5).setHeightInPoints(36);
    }
}
