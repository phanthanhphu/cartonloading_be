package org.bsl.cartonloading.buyers.lululemon.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.bsl.cartonloading.common.constants.AppConstants;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@Document(collection = AppConstants.Collection.PURCHASE_ORDERS)
public class PurchaseOrder {
    @Id
    private String id;
    private String buyerCode;
    @org.springframework.data.mongodb.core.mapping.Field("buyer_key")
    private String buyerKey = AppConstants.Buyer.LULULEMON;
    /** User-driven PO ownership; separate from business PO status. */
    private String pickedBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime pickedAt;
    /** Parent Packing Order for the order-scoped LULULEMON workflow. */
    private String orderId;
    private String factoryCode;
    private String poNumber;
    private String masterPo;
    private String style;
    private String description;
    private String color;
    private String size;

    /** Assigned by carton identification or the first physical product scan for this logical PO. */
    private String sku;
    private String skuAssignedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime skuAssignedAt;

    private Integer plannedCartons;
    private Integer pcsPerCarton;
    private Integer plannedTotalQty;
    private Integer remainderQty;
    /** Exact carton quantity plan captured from ALL_BP. Kept on PO so Cartons can be generated lazily without losing row-level packing distribution. */
    private List<Integer> cartonPlannedQuantities;

    /** Exact ALL_BP header captions in source-file order. */
    private List<String> allBpHeaders = new ArrayList<>();

    /**
     * Full source data from ALL_BP for this PO. Keys are the exact Excel header
     * captions and values are the cell display values. A list is used because one
     * PO can span multiple ALL_BP rows. Unknown/new columns are preserved too.
     */
    private List<Map<String, String>> allBpRows = new ArrayList<>();

    private String dcCode;
    private String destination;
    private String channel;
    private String packingPlan;
    private String salesOrderPts;
    private String shipMode;
    private String season;
    private String fwd;
    private String cartonBoxSize;
    private Double netWeightKg;
    private Double grossWeightKg;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate hod;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate exFtyDate;

    /** Shipping allocation date chosen by Sales; independent from ALL_BP HOD / Ex-Factory fields. */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate plannedShippingDate;
    private String shippingScheduledBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime shippingScheduledAt;

    private String status;

    /** Carton Weighing station session state for this logical PO. */
    private String weighingSessionStatus;
    /** PASS, PENDING, ISSUES: latest RFID review, independent of weight completion. */
    private String rfidReviewStatus;
    private String weighingStationCode;
    private String weighingStartedBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime weighingStartedAt;
    private String weighingStoppedBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime weighingStoppedAt;
    private String weighingCompletedBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime weighingCompletedAt;

    private String sourceFileName;
    private Integer sourceRowNumber;
    private String createdBy;
    private String updatedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
    /**
     * Legacy All_BP imports might have retained Size only in the raw source rows,
     * because older header aliases omitted SIZE. Recover it on API reads without
     * mutating live PO/carton/SSCC state or silently choosing among mixed sizes.
     * Re-import/reconcile explicitly if a historical PO contains different sizes.
     */
    public String getSize() {
        if (size != null && !size.isBlank()) return size;
        if (allBpRows == null || allBpRows.isEmpty()) return size;
        String candidate = null;
        for (Map<String, String> rawRow : allBpRows) {
            if (rawRow == null) continue;
            for (Map.Entry<String, String> cell : rawRow.entrySet()) {
                String header = org.bsl.cartonloading.common.importing.TextNormalizer.headerKey(cell.getKey());
                if (!java.util.Set.of("SIZE", "SIZES", "GARMENTSIZE", "APPARELSIZE", "PRODUCTSIZE").contains(header)) continue;
                String value = cell.getValue() == null ? null : cell.getValue().trim();
                if (value == null || value.isEmpty()) continue;
                if (candidate == null) candidate = value;
                else if (!candidate.equalsIgnoreCase(value)) return null; // historical mixed-size PO
            }
        }
        return candidate == null ? size : candidate;
    }

    // Compatibility aliases used by the order-scoped workflow. They intentionally
    // map to the original persisted field names so legacy fixed-namespace APIs
    // and the newer order-scoped APIs read/write the same business values.
    public String getStyleNumber() { return style; }
    public void setStyleNumber(String value) { this.style = value; }

    public Integer getTotalQty() { return plannedTotalQty; }
    public void setTotalQty(Integer value) { this.plannedTotalQty = value; }

    public Integer getQtyPerCarton() { return pcsPerCarton; }
    public void setQtyPerCarton(Integer value) { this.pcsPerCarton = value; }

    public Integer getCartonCount() { return plannedCartons; }
    public void setCartonCount(Integer value) { this.plannedCartons = value; }

    public Double getNetWeight() { return netWeightKg; }
    public void setNetWeight(Double value) { this.netWeightKg = value; }

    public Double getGrossWeight() { return grossWeightKg; }
    public void setGrossWeight(Double value) { this.grossWeightKg = value; }

    public Integer getSourceLineNo() { return sourceRowNumber; }
    public void setSourceLineNo(Integer value) { this.sourceRowNumber = value; }

}
