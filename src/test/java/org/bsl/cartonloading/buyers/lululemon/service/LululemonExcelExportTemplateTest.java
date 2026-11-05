package org.bsl.cartonloading.buyers.lululemon.service;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class LululemonExcelExportTemplateTest {
    @Test
    void officialTemplateHasActionAndExactlyTwentyNineBusinessColumns() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(
                "templates/Lululemon_PO_Import_Template.xlsx")) {
            assertNotNull(in, "Download/Export must ship the same official template");
            try (XSSFWorkbook wb = new XSSFWorkbook(in)) {
                var sheet = wb.getSheet(AppConstants.Excel.ALL_BP_SHEET);
                assertNotNull(sheet);
                var header = sheet.getRow(1);
                assertNotNull(header);
                assertEquals(30, header.getLastCellNum());
                var format = new DataFormatter();
                assertEquals(AppConstants.ExcelAction.ACTION_COLUMN, format.formatCellValue(header.getCell(0)));
                assertEquals("Master PO", format.formatCellValue(header.getCell(8)));
                assertEquals("PO", format.formatCellValue(header.getCell(9)));
                assertEquals("Packing Plan", format.formatCellValue(header.getCell(10)));
                assertEquals("SO (PTS)", format.formatCellValue(header.getCell(11)));
                assertEquals("Ship mode", format.formatCellValue(header.getCell(21)));
                var actualHeaders = new java.util.ArrayList<String>();
                for (int c = 1; c < header.getLastCellNum(); c++) {
                    actualHeaders.add(format.formatCellValue(header.getCell(c)));
                }
                assertEquals(AppConstants.AllBp.TEMPLATE_BUSINESS_COLUMN_COUNT, actualHeaders.size());
                assertFalse(actualHeaders.contains("FOB Price"));
                assertFalse(actualHeaders.contains("Care & Content Label"));
                assertFalse(actualHeaders.contains("China Inspection Tag"));
                assertFalse(actualHeaders.contains("FOB Amount"));
                assertFalse(actualHeaders.contains("Total FOB+Prcie Tag"));
                assertFalse(actualHeaders.contains("FOB Fty Price"));
                assertFalse(actualHeaders.contains("FOB Fty Amount"));
                assertEquals(7, AppConstants.AllBp.RETIRED_KEYS.size());
                assertFalse(sheet.getDataValidations().isEmpty(), "ACTION dropdown must stay in exported file");
            }
        }
    }
}
