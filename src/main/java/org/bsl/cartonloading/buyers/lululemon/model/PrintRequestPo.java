package org.bsl.cartonloading.buyers.lululemon.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrintRequestPo {
    private String poId;
    private String poNumber;
    private String factoryCode;
    private String style;
    private String color;
    private String size;
    private String sku;
    private Integer plannedCartons;
    private Integer plannedTotalQty;
    private String status;
}
