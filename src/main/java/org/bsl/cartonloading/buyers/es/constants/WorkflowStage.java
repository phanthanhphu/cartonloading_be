package org.bsl.cartonloading.buyers.es.constants;

/** Compile-time labels shared by API documentation groups. */
public final class WorkflowStage {
    private WorkflowStage() { }
    public static final String SALES_MASTER = "1. Sales - Master Data";
    public static final String PACKING_ASSIGN = "2. Packing - Assign Barcode for Carton";
    public static final String SALES_PLAN = "3. Sales - Shipment Planning";
    public static final String PACKING_WEIGHT = "4. Packing - Weight Check and Dispatch";
}
