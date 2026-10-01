package org.bsl.cartonloading.buyers.lululemon.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LululemonPrintRequestPo {
    private String poId;
    private String poNumber;
    private String factoryCode;
    private String style;
    private String sku;
    private Integer plannedCartons;
    private Integer plannedTotalQty;
    private String status;
}
