package org.bsl.cartonloading.buyers.lululemon.dto;

import java.util.ArrayList;
import java.util.List;

public class LululemonImportResult {
    private boolean success = true;
    private int totalRows;
    private int createdPos;
    private int updatedPos;
    private int createdCartons;
    private int createdItems;
    private final List<String> warnings = new ArrayList<>();

    public LululemonImportResult() {
    }

    /**
     * Compatibility constructor used by the order-scoped LULULEMON workflow.
     * The existing mutable DTO is intentionally retained because the legacy
     * fixed-namespace importer still populates it incrementally through setters.
     */
    public LululemonImportResult(
            boolean success,
            int createdPos,
            int createdCartons,
            int createdItems,
            int updatedPos,
            List<String> warnings
    ) {
        this.success = success;
        this.createdPos = createdPos;
        this.createdCartons = createdCartons;
        this.createdItems = createdItems;
        this.updatedPos = updatedPos;
        this.totalRows = createdPos + updatedPos;
        if (warnings != null) {
            this.warnings.addAll(warnings);
        }
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }
    public int getCreatedPos() { return createdPos; }
    public void setCreatedPos(int createdPos) { this.createdPos = createdPos; }
    public int getUpdatedPos() { return updatedPos; }
    public void setUpdatedPos(int updatedPos) { this.updatedPos = updatedPos; }
    public int getCreatedCartons() { return createdCartons; }
    public void setCreatedCartons(int createdCartons) { this.createdCartons = createdCartons; }
    public int getCreatedItems() { return createdItems; }
    public void setCreatedItems(int createdItems) { this.createdItems = createdItems; }
    public List<String> getWarnings() { return warnings; }
}
