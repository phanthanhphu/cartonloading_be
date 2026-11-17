package org.bsl.cartonloading.buyers.lululemon.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.bsl.cartonloading.buyers.lululemon.model.*;
import org.bsl.cartonloading.buyers.lululemon.repository.*;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only snapshot for one already-authorized Shipping Day. No updates to Cartons, Items or RFID. */
@Service
public class ShippingScheduleQcReportService {
    private final CartonRepository cartons;
    private final CartonItemRepository items;
    private final RfidScanSessionRepository sessions;
    private final PurchaseOrderRepository pos;

    public ShippingScheduleQcReportService(CartonRepository cartons, CartonItemRepository items,
                                           RfidScanSessionRepository sessions, PurchaseOrderRepository pos) {
        this.cartons = cartons;
        this.items = items;
        this.sessions = sessions;
        this.pos = pos;
    }

    public record PoQc(String poId, String poNumber, String factoryCode, int plannedCartons,
                       int generatedCartons, int completedCartons, int ssccAssigned,
                       int skuAssigned, int plannedItems, int assignedItems, int rfidPass, int rfidFailed, int rfidNotScanned,
                       int rfidScanning, int rfidMissingTags, int weightPass,
                       int scanDayPassAttempts, int scanDayFailedAttempts, int scanDayCartons) { }
    public record CartonQc(String poId, String poNumber, String factoryCode, int cartonNo,
                           boolean generated, String cartonId, String sscc18, boolean ssccAssigned,
                           String labelSku, int expectedItems, int skuAssignedItems, boolean skuAssigned,
                           String rfidStatus, int rfidExpectedQty, int rfidActualQty, int rfidMissingQty,
                           String rfidReason, String lastRfidAt, String weightStatus,
                           boolean completed, int scanDayAttempts, int scanDayPassAttempts, int scanDayFailedAttempts) { }
    public record ScanQc(String poId, String poNumber, int cartonNo, String sscc18,
                         String scannedAt, String status, String reason,
                         int expectedQty, int actualQty, String operator) { }
    public record Report(String shippingDate, String factoryCode, String scanDate,
                         List<PoQc> pos, List<CartonQc> cartons, List<ScanQc> scans) { }

    private static boolean present(String s) { return s != null && !s.isBlank(); }
    private static int number(Integer n) { return n == null ? 0 : Math.max(0, n); }
    private static String normalize(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT); }
    private static LocalDateTime scannedAt(RfidScanSession s) {
        return s.getFinishedAt() == null ? s.getStartedAt() : s.getFinishedAt();
    }
    private static final ZoneId SHIPPING_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static LocalDateTime vietnamTime(LocalDateTime recorded) {
        return recorded == null ? null : recorded.atZone(ZoneId.systemDefault())
                .withZoneSameInstant(SHIPPING_ZONE).toLocalDateTime();
    }
    private static boolean onDay(RfidScanSession s, LocalDate day) {
        LocalDateTime local = vietnamTime(scannedAt(s));
        return day != null && local != null && day.equals(local.toLocalDate());
    }
    private static String state(Carton carton) {
        return carton == null || !present(carton.getRfidStatus()) ? "NOT_SCANNED" : normalize(carton.getRfidStatus());
    }
    private static boolean pass(Carton c) {
        return c != null && AppConstants.RfidCheck.PASS.equals(c.getRfidStatus())
                && AppConstants.WeightStatus.PASS.equals(c.getWeightStatus());
    }

    /** Membership comes only from ShippingDayDetail which enforces Order/Factory/date access. */
    public Report report(PoShippingPlanService.ShippingDayDetail day, String orderId, LocalDate scanDate) {
        List<String> poIds = day.pos().stream().map(PoShippingPlanService.ShippingPo::id).toList();
        Map<String, PurchaseOrder> poMap = pos.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, AppConstants.Buyer.LULULEMON).stream()
                .filter(p -> poIds.contains(p.getId()) && Objects.equals(day.factoryCode(), p.getFactoryCode()))
                .collect(Collectors.toMap(PurchaseOrder::getId, Function.identity(), (a, b) -> a));
        List<Carton> relevant = poIds.isEmpty() ? List.of() : cartons.findByPoIdIn(poIds).stream()
                .filter(c -> Objects.equals(orderId, c.getOrderId())
                        && Objects.equals(AppConstants.Buyer.LULULEMON, c.getBuyerCode())
                        && Objects.equals(day.factoryCode(), c.getFactoryCode()))
                .toList();
        Map<String, List<Carton>> cartonsByPo = relevant.stream().collect(Collectors.groupingBy(Carton::getPoId));
        Map<String, List<CartonItem>> itemsByCarton = new HashMap<>();
        // Fetch per PO (not per carton) so partially-created item slots can be reported accurately.
        for (String poId : poMap.keySet()) {
            for (CartonItem item : items.findByPoIdOrderByCartonNoAscItemNoAsc(poId)) {
                if (Objects.equals(orderId, item.getOrderId()) && Objects.equals(AppConstants.Buyer.LULULEMON, item.getBuyerCode())) {
                    itemsByCarton.computeIfAbsent(item.getCartonId(), k -> new ArrayList<>()).add(item);
                }
            }
        }
        Map<String, List<RfidScanSession>> sessionsByCarton = new HashMap<>();
        List<String> cartonIds = relevant.stream().map(Carton::getId).filter(Objects::nonNull).toList();
        if (!cartonIds.isEmpty()) {
            for (RfidScanSession session : sessions.findByBuyerCodeAndCartonIdInOrderByStartedAtDesc(AppConstants.Buyer.LULULEMON, cartonIds)) {
                if (Objects.equals(orderId, session.getOrderId()) && poMap.containsKey(session.getPoId())) {
                    sessionsByCarton.computeIfAbsent(session.getCartonId(), k -> new ArrayList<>()).add(session);
                }
            }
        }
        List<PoQc> summaries = new ArrayList<>();
        List<CartonQc> details = new ArrayList<>();
        List<ScanQc> history = new ArrayList<>();
        for (PoShippingPlanService.ShippingPo membership : day.pos()) {
            PurchaseOrder po = poMap.get(membership.id());
            if (po == null) continue;
            int planned = number(po.getCartonCount());
            Map<Integer, Carton> existing = cartonsByPo.getOrDefault(po.getId(), List.of()).stream()
                    .filter(c -> c.getCartonNo() != null && c.getCartonNo() > 0)
                    .collect(Collectors.toMap(Carton::getCartonNo, Function.identity(), (a, b) -> a));
            int completed = 0, sscc = 0, sku = 0, plannedItems = 0, assignedItems = 0;
            int rfidPass = 0, rfidFailed = 0, never = 0;
            int scanning = 0, missingTags = 0, weightPass = 0, dayPass = 0, dayFailed = 0, dayCartons = 0;
            for (int no = 1; no <= planned; no++) {
                Carton carton = existing.get(no);
                List<CartonItem> slots = carton == null ? List.of() : itemsByCarton.getOrDefault(carton.getId(), List.of());
                int expected = carton != null ? number(carton.getPlannedQty()) : expectedQty(po, no);
                // Items are individual products in cartons, NOT carton quantities.
                // Aggregate actual assigned item slots and the expected packing quantity separately.
                long assigned = slots.stream().filter(item -> present(item.getExpectedSku())).count();
                plannedItems += expected;
                assignedItems += (int) assigned;
                boolean ssccOk = carton != null && present(carton.getSscc18());
                // Confirmed carton-label SKU AND every planned item slot must be populated.
                boolean skuOk = carton != null && present(carton.getLabelConfirmedSku())
                        && expected > 0 && assigned >= expected;
                String status = state(carton);
                List<RfidScanSession> attempts = carton == null ? List.of()
                        : sessionsByCarton.getOrDefault(carton.getId(), List.of());
                RfidScanSession latest = attempts.stream().filter(s -> s.getFinishedAt() != null)
                        .max(Comparator.comparing(ShippingScheduleQcReportService::scannedAt)).orElse(null);
                int dayAttempts = 0, cartonDayPass = 0, cartonDayFailed = 0;
                for (RfidScanSession attempt : attempts) {
                    if (attempt.getFinishedAt() == null) continue; // unfinished attempts are not PASS/FAILED counts
                    if (scanDate != null && !onDay(attempt, scanDate)) continue;
                    String attemptStatus = normalize(attempt.getStatus());
                    if ("PASS".equals(attemptStatus)) { dayPass++; cartonDayPass++; }
                    if ("FAILED".equals(attemptStatus)) { dayFailed++; cartonDayFailed++; }
                    dayAttempts++;
                    history.add(new ScanQc(po.getId(), po.getPoNumber(), no, carton.getSscc18(),
                            String.valueOf(vietnamTime(scannedAt(attempt))), attemptStatus, attempt.getReason(),
                            number(attempt.getExpectedQty()), number(attempt.getActualQty()),
                            present(attempt.getFinishedBy()) ? attempt.getFinishedBy() : attempt.getStartedBy()));
                }
                if (dayAttempts > 0) dayCartons++;
                if (pass(carton)) completed++;
                if (ssccOk) sscc++;
                if (skuOk) sku++;
                if ("PASS".equals(status)) rfidPass++;
                else if ("FAILED".equals(status)) rfidFailed++;
                else if ("SCANNING".equals(status)) scanning++;
                else never++;
                if (carton != null && AppConstants.WeightStatus.PASS.equals(carton.getWeightStatus())) weightPass++;
                int actual = latest == null ? (carton == null ? 0 : number(carton.getRfidVerifiedQty())) : number(latest.getActualQty());
                int target = latest == null ? expected : Math.max(expected, number(latest.getExpectedQty()));
                int missing = "FAILED".equals(status) ? Math.max(0, target - actual) : 0;
                if (missing > 0) missingTags += missing; // Number of missing item tags, not cartons with missing tags.
                details.add(new CartonQc(po.getId(), po.getPoNumber(), day.factoryCode(), no,
                        carton != null, carton == null ? null : carton.getId(), carton == null ? null : carton.getSscc18(), ssccOk,
                        carton == null ? null : carton.getLabelConfirmedSku(), expected, (int) assigned, skuOk,
                        status, target, actual, missing, latest == null ? null : latest.getReason(),
                        latest == null ? null : String.valueOf(vietnamTime(scannedAt(latest))),
                        carton == null ? null : carton.getWeightStatus(), pass(carton), dayAttempts,
                        cartonDayPass, cartonDayFailed));
            }
            summaries.add(new PoQc(po.getId(), po.getPoNumber(), day.factoryCode(), planned,
                    existing.size(), completed, sscc, sku, plannedItems, assignedItems, rfidPass, rfidFailed, never, scanning,
                    missingTags, weightPass, dayPass, dayFailed, dayCartons));
        }
        return new Report(day.shippingDate(), day.factoryCode(), scanDate == null ? null : scanDate.toString(),
                List.copyOf(summaries), List.copyOf(details), List.copyOf(history));
    }

    private static int expectedQty(PurchaseOrder po, int cartonNo) {
        List<Integer> exact = po.getCartonPlannedQuantities();
        if (exact != null && exact.size() >= cartonNo && exact.get(cartonNo - 1) != null) return number(exact.get(cartonNo - 1));
        int planned = number(po.getCartonCount());
        int perCarton = number(po.getQtyPerCarton());
        int total = number(po.getTotalQty());
        return cartonNo == planned ? Math.max(0, total - perCarton * Math.max(0, planned - 1)) : perCarton;
    }

    /** PO-level server-side filter. All missing/pending filters include planned but ungenerated cartons. */
    public static boolean matches(PoQc s, String cartonStatus, String ssccStatus, String skuStatus, String rfidStatus, LocalDate scanDate) {
        if (scanDate != null && s.scanDayCartons() == 0) return false;
        if (present(cartonStatus) && !("COMPLETED".equals(cartonStatus) ? s.completedCartons() > 0 :
                "INCOMPLETE".equals(cartonStatus) && s.completedCartons() < s.plannedCartons())) return false;
        if (present(ssccStatus) && !("ASSIGNED".equals(ssccStatus) ? s.ssccAssigned() > 0 :
                "MISSING".equals(ssccStatus) && s.ssccAssigned() < s.plannedCartons())) return false;
        if (present(skuStatus) && !("ASSIGNED".equals(skuStatus) ? s.skuAssigned() > 0 :
                "MISSING".equals(skuStatus) && s.skuAssigned() < s.plannedCartons())) return false;
        if (present(rfidStatus) && !(switch (rfidStatus) {
            case "PASS" -> scanDate != null ? s.scanDayPassAttempts() > 0 : s.rfidPass() > 0;
            case "FAILED" -> scanDate != null ? s.scanDayFailedAttempts() > 0 : s.rfidFailed() > 0;
            case "NOT_SCANNED" -> s.rfidNotScanned() > 0;
            case "MISSING_TAGS" -> s.rfidMissingTags() > 0;
            default -> false;
        })) return false;
        return true;
    }

    /** All selected carton issues must match the SAME carton (not unrelated cartons in the same PO). */
    public static boolean matchesCarton(CartonQc c, String cartonStatus, String ssccStatus,
                                        String skuStatus, String rfidStatus, LocalDate scanDate) {
        if (present(cartonStatus) && !("COMPLETED".equals(cartonStatus) ? c.completed() :
                "INCOMPLETE".equals(cartonStatus) && !c.completed())) return false;
        if (present(ssccStatus) && !("ASSIGNED".equals(ssccStatus) ? c.ssccAssigned() :
                "MISSING".equals(ssccStatus) && !c.ssccAssigned())) return false;
        if (present(skuStatus) && !("ASSIGNED".equals(skuStatus) ? c.skuAssigned() :
                "MISSING".equals(skuStatus) && !c.skuAssigned())) return false;
        if (present(rfidStatus) && !(switch (rfidStatus) {
            case "PASS" -> scanDate != null ? c.scanDayPassAttempts() > 0 : "PASS".equals(c.rfidStatus());
            case "FAILED" -> scanDate != null ? c.scanDayFailedAttempts() > 0 : "FAILED".equals(c.rfidStatus());
            case "NOT_SCANNED" -> "NOT_SCANNED".equals(c.rfidStatus());
            case "MISSING_TAGS" -> c.rfidMissingQty() > 0;
            default -> false;
        })) return false;
        return true;
    }

    /**
     * Keep the original customizable Shipping POs worksheet unchanged.
     * By default the existing QC summary adds Carton Completed and RFID Scan Result.
     * SSCC Assigned (cartons) and SKU Items Assigned (individual products) are
     * independent opt-in fields, appended on the SAME sheet.
     * PO membership is keyed by PO ID, never by non-unique PO numbers.
     */
    public byte[] appendExcel(byte[] original, Report report, Set<String> allowedPoIds,
                              LocalDate scanDate, boolean includeQcColumns,
                              boolean includeSsccAssigned, boolean includeSkuItemsAssigned) {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(original));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheet("Shipping POs");
            if (sheet == null) throw new IllegalStateException("Missing Shipping POs worksheet.");
            Row headers = sheet.getRow(2); // title in row 1, headers in row 3
            if (headers == null) throw new IllegalStateException("Missing Shipping POs column headings.");
            int firstQcColumn = headers.getLastCellNum();
            if (firstQcColumn < 1) throw new IllegalStateException("Shipping POs has no export columns.");

            CellStyle heading = workbook.createCellStyle();
            heading.cloneStyleFrom(headers.getCell(0).getCellStyle());
            heading.setWrapText(true);
            CellStyle body = workbook.createCellStyle();
            Row firstDataRow = sheet.getRow(3);
            Cell firstOriginalCell = firstDataRow == null ? null : firstDataRow.getCell(0);
            if (firstOriginalCell != null) body.cloneStyleFrom(firstOriginalCell.getCellStyle());
            body.setWrapText(true);
            body.setVerticalAlignment(VerticalAlignment.CENTER);
            if (firstOriginalCell == null) {
                body.setBorderTop(BorderStyle.THIN);
                body.setBorderBottom(BorderStyle.THIN);
                body.setBorderLeft(BorderStyle.THIN);
                body.setBorderRight(BorderStyle.THIN);
            }

            List<String> names = new ArrayList<>();
            List<Integer> widths = new ArrayList<>();
            if (includeQcColumns) { names.add("Carton Completed"); widths.add(23); }
            if (includeSsccAssigned) { names.add("SSCC Assigned"); widths.add(23); }
            if (includeSkuItemsAssigned) { names.add("SKU Items Assigned"); widths.add(26); }
            if (includeQcColumns) { names.add("RFID Scan Result"); widths.add(46); }
            for (int offset = 0; offset < names.size(); offset++) {
                Cell headerCell = headers.createCell(firstQcColumn + offset);
                headerCell.setCellValue(names.get(offset));
                headerCell.setCellStyle(heading);
                sheet.setColumnWidth(firstQcColumn + offset, widths.get(offset) * 256);
            }

            int rowNumber = 3;
            for (PoQc p : report.pos()) {
                if (!allowedPoIds.contains(p.poId())) continue;
                Row row = sheet.getRow(rowNumber++);
                if (row == null) throw new IllegalStateException("Shipping PO rows do not match the QC report.");
                int qcColumn = firstQcColumn;
                if (includeQcColumns) writeQcCell(row, qcColumn++, cartonCompletedCell(p), body);
                if (includeSsccAssigned) writeQcCell(row, qcColumn++, ssccAssignedCell(p), body);
                if (includeSkuItemsAssigned) writeQcCell(row, qcColumn++, skuItemsAssignedCell(p), body);
                if (includeQcColumns) writeQcCell(row, qcColumn, rfidCell(p, scanDate), body);
                if (includeQcColumns) row.setHeightInPoints(scanDate == null ? 57 : 73);
            }
            if (rowNumber - 1 != sheet.getLastRowNum()) {
                throw new IllegalStateException("Shipping PO row count does not match QC report.");
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to export Shipping Schedule QC report.", e);
        }
    }

    static String cartonCompletedCell(PoQc p) {
        return p.completedCartons() + " / " + p.plannedCartons();
    }

    static String ssccAssignedCell(PoQc p) {
        return p.ssccAssigned() + " / " + p.plannedCartons();
    }

    static String skuItemsAssignedCell(PoQc p) {
        return p.assignedItems() + " / " + p.plannedItems();
    }

    static String rfidCell(PoQc p, LocalDate scanDate) {
        String value = "PASS: " + p.rfidPass() + " | FAILED: " + p.rfidFailed()
                + "\nNOT SCANNED: " + p.rfidNotScanned() + " | SCANNING: " + p.rfidScanning()
                + "\nMISSING TAGS: " + p.rfidMissingTags();
        if (scanDate != null) {
            value += "\n" + scanDate + " scan attempts — PASS: " + p.scanDayPassAttempts()
                    + " | FAILED: " + p.scanDayFailedAttempts()
                    + " | cartons: " + p.scanDayCartons();
        }
        return value;
    }

    private static void writeQcCell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }
}
