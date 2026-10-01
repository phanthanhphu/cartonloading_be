package org.bsl.cartonloading.common.importing;

import java.util.ArrayList;
import java.util.List;

public class SpreadsheetImportResult {
    private String dataset;
    private ImportMode mode;
    private boolean applied;
    private int totalRows;
    private int validRows;
    private int created;
    private int updated;
    private int deleted;
    private int skipped;
    private List<ImportRowError> errors = new ArrayList<>();

    public static SpreadsheetImportResult rejected(String dataset, ImportMode mode, int totalRows, List<ImportRowError> errors) {
        SpreadsheetImportResult result = new SpreadsheetImportResult();
        result.setDataset(dataset);
        result.setMode(mode);
        result.setApplied(false);
        result.setTotalRows(totalRows);
        long invalidDataRows = errors == null ? 0 : errors.stream()
                .map(ImportRowError::getRowNumber).filter(row -> row > 1).distinct().count();
        result.setValidRows(Math.max(0, totalRows - (int) invalidDataRows));
        result.setSkipped(totalRows);
        result.setErrors(errors);
        return result;
    }

    public String getDataset() { return dataset; }
    public void setDataset(String dataset) { this.dataset = dataset; }
    public ImportMode getMode() { return mode; }
    public void setMode(ImportMode mode) { this.mode = mode; }
    public boolean isApplied() { return applied; }
    public void setApplied(boolean applied) { this.applied = applied; }
    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }
    public int getValidRows() { return validRows; }
    public void setValidRows(int validRows) { this.validRows = validRows; }
    public int getCreated() { return created; }
    public void setCreated(int created) { this.created = created; }
    public int getUpdated() { return updated; }
    public void setUpdated(int updated) { this.updated = updated; }
    public int getDeleted() { return deleted; }
    public void setDeleted(int deleted) { this.deleted = deleted; }
    public int getSkipped() { return skipped; }
    public void setSkipped(int skipped) { this.skipped = skipped; }
    public List<ImportRowError> getErrors() { return errors; }
    public void setErrors(List<ImportRowError> errors) { this.errors = errors == null ? new ArrayList<>() : errors; }
}
