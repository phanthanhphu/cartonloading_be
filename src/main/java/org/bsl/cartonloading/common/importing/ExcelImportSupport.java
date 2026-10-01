package org.bsl.cartonloading.common.importing;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Component
public class ExcelImportSupport {

    private static final long MAX_SIZE_BYTES = 20L * 1024L * 1024L;

    private static final List<DateTimeFormatter> DATE_FORMATTERS = Arrays.asList(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/uuuu"),
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("dd-MM-uuuu"),
            DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatter.ofPattern("MM/dd/uuuu"),
            DateTimeFormatter.ofPattern("M/d/uuuu")
    );

    private final DataFormatter formatter = new DataFormatter(Locale.US);

    public Workbook openWorkbook(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ImportValidationException("Excel file is required");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new ImportValidationException("Excel file must not exceed 20 MB");
        }

        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) {
            throw new ImportValidationException("Only .xlsx or .xls files are supported");
        }

        try {
            return WorkbookFactory.create(file.getInputStream());
        } catch (IOException ex) {
            throw new ImportValidationException("Cannot read Excel file: " + ex.getMessage());
        }
    }

    public Sheet requiredSheet(Workbook workbook, String expectedSheetName) {
        for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
            Sheet sheet = workbook.getSheetAt(index);
            if (sheet.getSheetName().trim().equalsIgnoreCase(expectedSheetName)) {
                return sheet;
            }
        }
        throw new ImportValidationException("Excel does not contain required sheet: " + expectedSheetName);
    }

    public FormulaEvaluator evaluator(Workbook workbook) {
        return workbook.getCreationHelper().createFormulaEvaluator();
    }

    public void requireHeaders(Sheet sheet, FormulaEvaluator evaluator, HeaderRequirement... requirements) {
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        if (headerRow == null) {
            throw new ImportValidationException("Sheet " + sheet.getSheetName() + " does not have a header row");
        }

        for (HeaderRequirement requirement : requirements) {
            String actual = text(headerRow, requirement.columnIndex(), evaluator);
            String actualKey = TextNormalizer.headerKey(actual);
            boolean matched = Arrays.stream(requirement.acceptedHeaders())
                    .map(TextNormalizer::headerKey)
                    .anyMatch(actualKey::equals);

            if (!matched) {
                throw new ImportValidationException(
                        "Invalid header at column " + excelColumn(requirement.columnIndex())
                                + " in sheet " + sheet.getSheetName()
                                + ". Expected " + String.join(" / ", requirement.acceptedHeaders())
                                + ", received " + (actual == null ? "blank" : "'" + actual + "'")
                );
            }
        }
    }

    public boolean isBlank(Row row, int maxColumnExclusive, FormulaEvaluator evaluator) {
        if (row == null) {
            return true;
        }
        for (int col = 0; col < maxColumnExclusive; col++) {
            if (text(row, col, evaluator) != null) {
                return false;
            }
        }
        return true;
    }

    public String text(Row row, int columnIndex, FormulaEvaluator evaluator) {
        Cell cell = cell(row, columnIndex);
        if (cell == null) {
            return null;
        }

        // DataFormatter normally preserves the visible Excel value, including leading zeroes.
        // Some customer workbooks contain malformed/hidden custom formats such as ";;".
        // Apache POI 5.2.x can log an IndexOutOfBoundsException while trying to parse those
        // formats. A numeric cell using only semicolon sections is visually blank in Excel,
        // so handle it before DataFormatter and avoid the POI failure/noise altogether.
        if (hasSemicolonOnlyFormat(cell) && isNumericLike(cell, evaluator)) {
            return null;
        }

        try {
            return TextNormalizer.trimToNull(formatter.formatCellValue(cell, evaluator));
        } catch (RuntimeException ex) {
            // Do not reject an entire import because one cell has a broken custom format.
            // Fall back to the evaluated/raw cell value while keeping numeric output plain
            // (no scientific notation) for identifiers such as PO/SKU values.
            return TextNormalizer.trimToNull(fallbackText(cell, evaluator));
        }
    }

    private boolean hasSemicolonOnlyFormat(Cell cell) {
        try {
            String format = cell.getCellStyle() == null ? null : cell.getCellStyle().getDataFormatString();
            return format != null && format.trim().matches(";{1,4}");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isNumericLike(Cell cell, FormulaEvaluator evaluator) {
        if (cell.getCellType() == CellType.NUMERIC) {
            return true;
        }
        if (cell.getCellType() != CellType.FORMULA) {
            return false;
        }
        try {
            CellValue value = evaluator == null ? null : evaluator.evaluate(cell);
            return value != null && value.getCellType() == CellType.NUMERIC;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String fallbackText(Cell cell, FormulaEvaluator evaluator) {
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA && evaluator != null) {
            try {
                CellValue value = evaluator.evaluate(cell);
                if (value == null || value.getCellType() == CellType.BLANK) return null;
                if (value.getCellType() == CellType.STRING) return value.getStringValue();
                if (value.getCellType() == CellType.BOOLEAN) return Boolean.toString(value.getBooleanValue());
                if (value.getCellType() == CellType.NUMERIC) return plainNumber(value.getNumberValue());
                if (value.getCellType() == CellType.ERROR) return null;
            } catch (RuntimeException ignored) {
                // Fall through to the cached/raw cell value below.
            }
            type = cell.getCachedFormulaResultType();
        }

        return switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> plainNumber(cell.getNumericCellValue());
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case BLANK, ERROR, _NONE, FORMULA -> null;
        };
    }

    private String plainNumber(double value) {
        if (!Double.isFinite(value)) return null;
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    public BigDecimal decimal(Row row, int columnIndex, FormulaEvaluator evaluator) {
        Cell cell = cell(row, columnIndex);
        if (cell == null) {
            return null;
        }

        CellType cellType = cell.getCellType();
        if (cellType == CellType.FORMULA) {
            CellValue result = evaluator.evaluate(cell);
            if (result == null || result.getCellType() == CellType.BLANK) {
                return null;
            }
            if (result.getCellType() == CellType.NUMERIC) {
                return BigDecimal.valueOf(result.getNumberValue());
            }
            return parseDecimal(result.getStringValue(), excelColumn(columnIndex));
        }
        if (cellType == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }
        return parseDecimal(text(row, columnIndex, evaluator), excelColumn(columnIndex));
    }

    public LocalDate localDate(Row row, int columnIndex, FormulaEvaluator evaluator) {
        Cell cell = cell(row, columnIndex);
        if (cell == null) {
            return null;
        }

        double serial = Double.NaN;
        if (cell.getCellType() == CellType.NUMERIC) {
            serial = cell.getNumericCellValue();
        } else if (cell.getCellType() == CellType.FORMULA) {
            CellValue value = evaluator.evaluate(cell);
            if (value != null && value.getCellType() == CellType.NUMERIC) {
                serial = value.getNumberValue();
            }
        }
        if (!Double.isNaN(serial) && DateUtil.isValidExcelDate(serial)) {
            return DateUtil.getJavaDate(serial)
                    .toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
        }

        String raw = text(row, columnIndex, evaluator);
        if (raw == null) {
            return null;
        }

        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(raw, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }
        throw new ImportValidationException("Invalid date '" + raw + "' in column " + excelColumn(columnIndex));
    }

    public String excelColumn(int zeroBasedIndex) {
        int value = zeroBasedIndex + 1;
        StringBuilder result = new StringBuilder();
        while (value > 0) {
            int remainder = (value - 1) % 26;
            result.insert(0, (char) ('A' + remainder));
            value = (value - 1) / 26;
        }
        return result.toString();
    }

    private Cell cell(Row row, int columnIndex) {
        return row == null ? null : row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
    }

    private BigDecimal parseDecimal(String raw, String column) {
        String value = TextNormalizer.trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            String normalized = value.replace(" ", "");
            if (normalized.contains(",") && normalized.contains(".")) {
                normalized = normalized.replace(",", "");
            } else if (normalized.contains(",")) {
                normalized = normalized.replace(",", ".");
            }
            return new BigDecimal(normalized);
        } catch (NumberFormatException ex) {
            throw new ImportValidationException("Invalid number '" + raw + "' in column " + column);
        }
    }

    public static class HeaderRequirement {

        private final int columnIndex;
        private final String[] acceptedHeaders;

        public HeaderRequirement(int columnIndex, String... acceptedHeaders) {
            this.columnIndex = columnIndex;
            this.acceptedHeaders = acceptedHeaders;
        }

        public int columnIndex() {
            return columnIndex;
        }

        public String[] acceptedHeaders() {
            return acceptedHeaders;
        }
    }
}
