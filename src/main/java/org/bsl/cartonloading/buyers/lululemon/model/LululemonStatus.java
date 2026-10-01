package org.bsl.cartonloading.buyers.lululemon.model;

public final class LululemonStatus {
    private LululemonStatus() {}

    public static final String PO_NOT_STARTED = "NOT_STARTED";
    public static final String PO_PACKING = "PACKING";
    public static final String PO_WAITING_EX_FTY = "WAITING_EX_FTY";
    public static final String PO_WAITING_LABEL = "WAITING_LABEL";
    public static final String PO_WAITING_SSCC = "WAITING_SSCC";
    public static final String PO_READY_TO_SHIP = "READY_TO_SHIP";

    public static final String CARTON_WAITING = "READY_TO_PACK";
    public static final String CARTON_PACKING = "PACKING";
    public static final String CARTON_FINISHED = "FINISHED";
    public static final String CARTON_WAITING_SSCC = "LABEL_CONFIRMED";
    public static final String CARTON_READY_TO_SHIP = "READY_TO_SHIP";

    public static final String ITEM_WAITING = "PENDING";
    public static final String ITEM_SCANNED = "PASS";
}
