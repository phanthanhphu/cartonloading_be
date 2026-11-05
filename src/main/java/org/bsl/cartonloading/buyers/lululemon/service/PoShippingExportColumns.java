package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.common.importing.TextNormalizer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only, whitelisted column contract for the LULULEMON Shipping Date Excel export. */
public final class PoShippingExportColumns {
    private PoShippingExportColumns() {}

    private static final Map<String, String> LABELS;
    public static final List<String> DEFAULT = List.of(
            "DC_CODE", "CHANNEL", "PO", "STYLE", "DESCRIPTION", "COLOR", "QTY", "PCS_CTN",
            "CTNS", "REMAINDER", "REMARK", "SHIP_MODE");

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("DC_CODE", "DC Code");
        labels.put("CHANNEL", "Channel");
        labels.put("PO", "PO");
        labels.put("STYLE", "Style");
        labels.put("DESCRIPTION", "Description");
        labels.put("COLOR", "Color Description");
        labels.put("QTY", "Qty");
        labels.put("PCS_CTN", "Pcs/Ctn");
        labels.put("CTNS", "Ctns");
        labels.put("REMAINDER", "Remainder Qty");
        labels.put("REMARK", "Remark");
        labels.put("SHIP_MODE", "Ship Mode");
        labels.put("DESTINATION", "Destination");
        labels.put("MASTER_PO", "Master PO");
        labels.put("PACKING_PLAN", "Packing Plan");
        labels.put("SALES_ORDER_PTS", "SO (PTS)");
        labels.put("SKU", "SKU");
        labels.put("FACTORY", "Factory");
        labels.put("SHIPPING_DATE", "Shipping Date");
        labels.put("ORDER", "Order");
        labels.put("STT", "STT");
        labels.put("PLANNED_CARTONS", "Planned Cartons");
        labels.put("SEASON", "Season");
        labels.put("FWD", "FWD");
        labels.put("CARTON_BOX_SIZE", "Carton Box Size");
        labels.put("NW", "NW");
        labels.put("GW", "GW");
        labels.put("EX_FTY_DATE", "Ex-Factory Date");
        labels.put("HOD", "HOD (Hand over date)");
        labels.put("SGS_TESTING", "SGS testing");
        labels.put("GB_TESTING", "GB testing");
        labels.put("BV_INSPECTION", "BV inspection");
        labels.put("DP_PERCENT", "DP%");
        labels.put("MPR_NUMBER", "MPR NUMBER");
        labels.put("REMARK_2", "Remark 2");
        LABELS = Collections.unmodifiableMap(labels);
    }

    public static List<String> resolve(List<String> requested) {
        if (requested == null) return DEFAULT;
        if (requested.isEmpty() || requested.size() > LABELS.size()) {
            throw new IllegalArgumentException("Select at least one valid export column.");
        }
        List<String> selected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String key : requested) {
            if (key == null || !LABELS.containsKey(key) || !seen.add(key)) {
                throw new IllegalArgumentException("Invalid or duplicate Excel export column: " + key);
            }
            selected.add(key);
        }
        return List.copyOf(selected);
    }

    public static String label(String key) { return LABELS.get(key); }

    public static Object cell(String key, PurchaseOrder po, LocalDate day, String orderName, int index) {
        return switch (key) {
            case "DC_CODE" -> po.getDcCode();
            case "CHANNEL" -> po.getChannel();
            case "PO" -> po.getPoNumber();
            case "STYLE" -> po.getStyleNumber();
            case "DESCRIPTION" -> po.getDescription();
            case "COLOR" -> po.getColor();
            case "QTY" -> po.getTotalQty();
            case "PCS_CTN" -> pcsPerCarton(po);
            case "CTNS" -> cartonMetric(po, false);
            case "REMAINDER" -> cartonMetric(po, true);
            case "REMARK" -> rawValues(po, "REMARK");
            case "SHIP_MODE" -> po.getShipMode();
            case "DESTINATION" -> po.getDestination();
            case "MASTER_PO" -> po.getMasterPo();
            case "PACKING_PLAN" -> po.getPackingPlan();
            case "SALES_ORDER_PTS" -> po.getSalesOrderPts();
            case "SKU" -> po.getSku();
            case "FACTORY" -> po.getFactoryCode();
            case "SHIPPING_DATE" -> day;
            case "ORDER" -> orderName;
            case "STT" -> index;
            case "PLANNED_CARTONS" -> po.getPlannedCartons();
            case "SEASON" -> po.getSeason();
            case "FWD" -> po.getFwd();
            case "CARTON_BOX_SIZE" -> po.getCartonBoxSize();
            case "NW" -> po.getNetWeightKg();
            case "GW" -> po.getGrossWeightKg();
            case "EX_FTY_DATE" -> po.getExFtyDate();
            case "HOD" -> po.getHod();
            case "SGS_TESTING", "GB_TESTING", "BV_INSPECTION", "DP_PERCENT", "MPR_NUMBER", "REMARK_2" -> rawValues(po, key);
            default -> throw new IllegalArgumentException("Unsupported export column: " + key);
        };
    }

    private static String canonical(String header) {
        String name = TextNormalizer.headerKey(header);
        return switch (name) {
            case "DCCODE" -> "DC_CODE";
            case "CHANEL", "CHANNEL" -> "CHANNEL";
            case "COLORDESCRIPTION", "COLOR", "COLOURDESCRIPTION" -> "COLOR";
            case "PCSCTN", "PCSPERCTN", "PCSPERCARTON" -> "PCS_CTN";
            case "QTY", "QTYTOTAL", "TOTALQTY", "QUANTITY" -> "QTY";
            case "CTNS", "CARTONS", "CARTONQTY", "TOTALCARTONS" -> "CTNS";
            case "SHIPMODE", "SHIPPINGMODE" -> "SHIP_MODE";
            case "SGSTESTING" -> "SGS_TESTING";
            case "GBTESTING" -> "GB_TESTING";
            case "BVINSPECTION" -> "BV_INSPECTION";
            case "DP", "DPPERCENT" -> "DP_PERCENT";
            case "MPRNUMBER", "MPRNO" -> "MPR_NUMBER";
            case "REMARK2", "REMARK02" -> "REMARK_2";
            default -> name;
        };
    }

    private static String rawValues(PurchaseOrder po, String key) {
        Set<String> distinct = new LinkedHashSet<>();
        if (po.getAllBpRows() != null) for (Map<String, String> sourceRow : po.getAllBpRows()) {
            if (sourceRow == null) continue;
            for (Map.Entry<String, String> entry : sourceRow.entrySet()) {
                if (key.equals(canonical(entry.getKey())) && entry.getValue() != null && !entry.getValue().isBlank()) {
                    distinct.add(entry.getValue().trim());
                }
            }
        }
        return String.join(" | ", distinct);
    }

    private static Object pcsPerCarton(PurchaseOrder po) {
        String distinct = rawValues(po, "PCS_CTN");
        if (distinct.contains(" | ")) return distinct;
        if (po.getPcsPerCarton() != null) return po.getPcsPerCarton();
        return distinct;
    }

    private static BigDecimal numeric(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return new BigDecimal(raw.trim().replace(",", "")); }
        catch (NumberFormatException ignored) { return null; }
    }

    /** Match All POs: sum floor(Qty / Pcs/Ctn) and per-source-row remainders, never round up. */
    private static Object cartonMetric(PurchaseOrder po, boolean remainder) {
        BigDecimal total = BigDecimal.ZERO;
        boolean calculated = false;
        if (po.getAllBpRows() != null) for (Map<String, String> sourceRow : po.getAllBpRows()) {
            if (sourceRow == null) continue;
            BigDecimal qty = null, pcs = null;
            for (Map.Entry<String, String> cell : sourceRow.entrySet()) {
                String canonical = canonical(cell.getKey());
                if ("QTY".equals(canonical)) qty = numeric(cell.getValue());
                if ("PCS_CTN".equals(canonical)) pcs = numeric(cell.getValue());
            }
            if (qty == null || pcs == null || qty.signum() <= 0 || pcs.signum() <= 0) continue;
            BigDecimal full = qty.divide(pcs, 0, RoundingMode.DOWN);
            total = total.add(remainder ? qty.subtract(full.multiply(pcs)) : full);
            calculated = true;
        }
        if (!calculated && po.getTotalQty() != null && po.getPcsPerCarton() != null
                && po.getTotalQty() > 0 && po.getPcsPerCarton() > 0) {
            BigDecimal qty = BigDecimal.valueOf(po.getTotalQty());
            BigDecimal pcs = BigDecimal.valueOf(po.getPcsPerCarton());
            BigDecimal full = qty.divide(pcs, 0, RoundingMode.DOWN);
            total = remainder ? qty.subtract(full.multiply(pcs)) : full;
            calculated = true;
        }
        if (!calculated && !remainder && po.getAllBpRows() != null) {
            BigDecimal imported = BigDecimal.ZERO;
            boolean hasImportedCartons = false;
            for (Map<String, String> sourceRow : po.getAllBpRows()) {
                if (sourceRow == null) continue;
                for (Map.Entry<String, String> entry : sourceRow.entrySet()) {
                    if (!"CTNS".equals(canonical(entry.getKey())) || entry.getValue() == null || entry.getValue().isBlank()) continue;
                    BigDecimal amount = numeric(entry.getValue());
                    if (amount == null) return null;
                    imported = imported.add(amount);
                    hasImportedCartons = true;
                }
            }
            if (hasImportedCartons) return imported.stripTrailingZeros();
        }
        return calculated ? total.stripTrailingZeros() : null;
    }
}
