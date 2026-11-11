package org.bsl.cartonloading.buyers.lululemon.service;

import org.apache.poi.ss.usermodel.*;
import org.bson.Document;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.bsl.cartonloading.buyers.es.service.PackingOrderService;
import org.bsl.cartonloading.buyers.lululemon.dto.PoShippingPlanRequest;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.repository.PurchaseOrderRepository;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.bsl.cartonloading.service.RequestActor;
import org.bsl.cartonloading.service.SelectedFactoryScope;
import org.bsl.cartonloading.security.AccessControl;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.awt.Color;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Order-scoped Shipping Days: one PO may belong to at most one Shipping Date.
 * Membership is persisted on the PO; each (Factory, date) is presented as one dated item.
 * No carton, RFID, weight, Ex-Factory, or HOD fields are changed.
 *
 * plannedShippingDate remains the first assigned membership as a legacy-compatible
 * summary, so existing Scheduled/Unscheduled PO filters continue to work.
 * Old POs with only plannedShippingDate are included without a data migration.
 */
@Service
public class PoShippingPlanService {
    private static final String SUBMISSION_COLLECTION = "po_shipping_day_submissions";
    private static final ZoneId SHIPPING_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String SUBMITTED = "SUBMITTED";
    private static final String REOPENED = "REOPENED";

    private final PackingOrderService orders;
    private final PurchaseOrderRepository pos;
    private final BuyerFactoryAccessService factoryAccess;
    private final FactoryCatalogService factories;
    private final MongoTemplate mongo;
    private final AuditLogService audit;
    private final SelectedFactoryScope selectedFactoryScope;
    private final AccessControl accessControl;

    public record ShippingDay(String shippingDate, String factoryCode, int poCount,
                              boolean submitted, boolean autoSubmitted, boolean reopened,
                              String submittedBy, String submittedAt) { }
    public record ShippingPo(String id, String poNumber, String masterPo, String style,
                             String sku, String destination, Integer quantity,
                             Integer plannedCartons, String packingPlan, String salesOrderPts) { }
    public record ShippingDayDetail(String shippingDate, String factoryCode, List<ShippingPo> pos,
                                    boolean submitted, boolean autoSubmitted, boolean reopened,
                                    String submittedBy, String submittedAt) { }
    private record SubmissionState(boolean submitted, boolean autoSubmitted, boolean reopened,
                                   String submittedBy, String submittedAt) { }

    public PoShippingPlanService(PackingOrderService orders, PurchaseOrderRepository pos,
                                 BuyerFactoryAccessService factoryAccess, FactoryCatalogService factories,
                                 MongoTemplate mongo, AuditLogService audit,
                                 SelectedFactoryScope selectedFactoryScope, AccessControl accessControl) {
        this.orders = orders;
        this.pos = pos;
        this.factoryAccess = factoryAccess;
        this.factories = factories;
        this.mongo = mongo;
        this.audit = audit;
        this.selectedFactoryScope = selectedFactoryScope;
        this.accessControl = accessControl;
    }

    private void requireOrder(String buyer, String orderId) {
        if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(buyer)) {
            throw new IllegalArgumentException("Shipping Days are available for LULULEMON only.");
        }
        orders.getEntity(AppConstants.Buyer.LULULEMON, orderId);
    }

    private String selectedFactory(String providedFactory) {
        String factory = selectedFactoryScope.constrain(AppConstants.Buyer.LULULEMON, providedFactory);
        if (factory == null || factory.isBlank()) {
            throw new IllegalArgumentException("Select a Factory before managing Shipping Days.");
        }
        factory = factory.trim().toUpperCase(Locale.ROOT);
        factories.requireActive(AppConstants.Buyer.LULULEMON, factory);
        factoryAccess.assertAssignedFactoryAccess(factory);
        return factory;
    }

    private static SortedSet<LocalDate> dates(PurchaseOrder po) {
        SortedSet<LocalDate> dates = new TreeSet<>();
        if (po.getShippingDates() != null) {
            for (LocalDate date : po.getShippingDates()) if (date != null) dates.add(date);
        }
        if (po.getPlannedShippingDate() != null) dates.add(po.getPlannedShippingDate());
        return dates;
    }

    private List<PurchaseOrder> factoryPos(String orderId, String factory) {
        return pos.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, AppConstants.Buyer.LULULEMON)
                .stream().filter(po -> factory.equalsIgnoreCase(po.getFactoryCode())).toList();
    }

    // Keep submission state separate from PO/carton data. For dates reached in Vietnam time,
    // the effective state is automatically SUBMITTED even if no browser or server job ran.
    // A past-date authorized Unsubmit is an explicit REOPENED override until resubmission.
    private String submissionId(String orderId, String factory, LocalDate day) {
        return AppConstants.Buyer.LULULEMON + "|" + orderId + "|" + factory + "|" + day;
    }

    private Document savedSubmission(String orderId, String factory, LocalDate day) {
        return mongo.findById(submissionId(orderId, factory, day), Document.class, SUBMISSION_COLLECTION);
    }

    private SubmissionState submissionState(String orderId, String factory, LocalDate day) {
        Document saved = savedSubmission(orderId, factory, day);
        String state = saved == null ? "" : saved.getString("state");
        boolean manual = SUBMITTED.equals(state);
        boolean reopened = REOPENED.equals(state);
        boolean automatic = !manual && !reopened && !day.isAfter(LocalDate.now(SHIPPING_ZONE));
        return new SubmissionState(manual || automatic, automatic, reopened,
                manual ? saved.getString("submittedBy") : automatic ? "SYSTEM" : null,
                manual ? saved.getString("submittedAt") : automatic ? day.atStartOfDay().toString() : null);
    }

    private ShippingDay shippingDay(String orderId, String factory, LocalDate day, int count) {
        SubmissionState status = submissionState(orderId, factory, day);
        return new ShippingDay(day.toString(), factory, count,
                status.submitted(), status.autoSubmitted(), status.reopened(),
                status.submittedBy(), status.submittedAt());
    }

    private ShippingDayDetail shippingDayDetail(String orderId, String factory, LocalDate day, List<ShippingPo> rows) {
        SubmissionState status = submissionState(orderId, factory, day);
        return new ShippingDayDetail(day.toString(), factory, rows,
                status.submitted(), status.autoSubmitted(), status.reopened(),
                status.submittedBy(), status.submittedAt());
    }

    private void assertEditable(String orderId, String factory, LocalDate day) {
        if (submissionState(orderId, factory, day).submitted()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This Shipping Schedule has been submitted and is read-only. An authorized user must Unsubmit it first.");
        }
    }

    private void deleteSubmission(String orderId, String factory, LocalDate day) {
        mongo.remove(Query.query(Criteria.where("_id").is(submissionId(orderId, factory, day))),
                SUBMISSION_COLLECTION);
    }

    /** Manually finalize and lock an existing Shipping Date for all users, including Admin. */
    public synchronized ShippingDay submitDay(String buyer, String orderId, String factoryCode, LocalDate day) {
        requireOrder(buyer, orderId);
        String factory = selectedFactory(factoryCode);
        if (day == null) throw new IllegalArgumentException("Select a valid Shipping Date.");
        int count = (int) factoryPos(orderId, factory).stream().filter(po -> dates(po).contains(day)).count();
        if (count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipping Schedule not found.");
        if (submissionState(orderId, factory, day).submitted())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Shipping Schedule is already submitted.");
        String actor = RequestActor.current();
        String now = LocalDateTime.now(SHIPPING_ZONE).toString();
        mongo.upsert(Query.query(Criteria.where("_id").is(submissionId(orderId, factory, day))),
                new Update().set("buyerCode", AppConstants.Buyer.LULULEMON)
                        .set("orderId", orderId).set("factoryCode", factory)
                        .set("shippingDate", day.toString()).set("state", SUBMITTED)
                        .set("submittedBy", actor).set("submittedAt", now)
                        .set("updatedBy", actor).set("updatedAt", now), SUBMISSION_COLLECTION);
        audit.logChange(actor, AppConstants.AuditAction.PO_SHIPPING_PLAN_SUBMIT,
                AppConstants.AuditResource.PO_SHIPPING_PLAN, orderId,
                "Submitted Shipping Schedule " + day + " for Factory " + factory,
                AppConstants.Buyer.LULULEMON, factory, null, null, null, null);
        return shippingDay(orderId, factory, day, count);
    }

    /** Admin or explicitly authorized user unlocks; an already reached date remains open until Submit. */
    public synchronized ShippingDay unsubmitDay(String buyer, String orderId, String factoryCode, LocalDate day) {
        requireOrder(buyer, orderId);
        if (!accessControl.canReopenShippingSchedule())
            throw new AccessDeniedException("Reopen Submitted Shipping Schedule permission is required.");
        String factory = selectedFactory(factoryCode);
        if (day == null) throw new IllegalArgumentException("Select a valid Shipping Date.");
        int count = (int) factoryPos(orderId, factory).stream().filter(po -> dates(po).contains(day)).count();
        if (count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipping Schedule not found.");
        if (!submissionState(orderId, factory, day).submitted())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Shipping Schedule is not submitted.");
        String actor = RequestActor.current();
        if (day.isAfter(LocalDate.now(SHIPPING_ZONE))) {
            // Future days still auto-submit when their date arrives.
            deleteSubmission(orderId, factory, day);
        } else {
            // Never immediately re-submit an overdue day after an authorized user unlocks it.
            mongo.upsert(Query.query(Criteria.where("_id").is(submissionId(orderId, factory, day))),
                    new Update().set("buyerCode", AppConstants.Buyer.LULULEMON)
                            .set("orderId", orderId).set("factoryCode", factory)
                            .set("shippingDate", day.toString()).set("state", REOPENED)
                            .unset("submittedBy").unset("submittedAt")
                            .set("updatedBy", actor)
                            .set("updatedAt", LocalDateTime.now(SHIPPING_ZONE).toString()), SUBMISSION_COLLECTION);
        }
        audit.logChange(actor, AppConstants.AuditAction.PO_SHIPPING_PLAN_UNSUBMIT,
                AppConstants.AuditResource.PO_SHIPPING_PLAN, orderId,
                "Unsubmitted Shipping Schedule " + day + " for Factory " + factory,
                AppConstants.Buyer.LULULEMON, factory, null, null, null, null);
        return shippingDay(orderId, factory, day, count);
    }

    public List<ShippingDay> list(String buyer, String orderId, String factoryCode) {
        requireOrder(buyer, orderId);
        String factory = selectedFactory(factoryCode);
        Map<LocalDate, Integer> counts = new TreeMap<>();
        factoryPos(orderId, factory).forEach(po -> dates(po).forEach(day -> counts.merge(day, 1, Integer::sum)));
        return counts.entrySet().stream()
                .map(entry -> shippingDay(orderId, factory, entry.getKey(), entry.getValue()))
                .toList();
    }

    public ShippingDayDetail detail(String buyer, String orderId, String factoryCode, LocalDate day) {
        requireOrder(buyer, orderId);
        String factory = selectedFactory(factoryCode);
        if (day == null) throw new IllegalArgumentException("Select a valid Shipping Date.");
        List<ShippingPo> included = factoryPos(orderId, factory).stream()
                .filter(po -> dates(po).contains(day))
                .map(po -> new ShippingPo(po.getId(), po.getPoNumber(), po.getMasterPo(),
                        po.getStyleNumber(), po.getSku(), po.getDestination(), po.getTotalQty(),
                        po.getPlannedCartons(), po.getPackingPlan(), po.getSalesOrderPts()))
                .toList();
        if (included.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipping Date not found.");
        return shippingDayDetail(orderId, factory, day, included);
    }

    /** A PO may only be assigned once within its Order, regardless of Shipping Date. */
    public synchronized Map<String, Object> schedule(String buyer, String orderId, PoShippingPlanRequest request) {
        requireOrder(buyer, orderId);
        if (request == null || request.shippingDate() == null) {
            throw new IllegalArgumentException("Select a shipping date.");
        }
        List<String> rawIds = request.poIds();
        if (rawIds == null || rawIds.isEmpty() || rawIds.size() > 200) {
            throw new IllegalArgumentException("Select 1 to 200 POs per submission.");
        }
        LinkedHashSet<String> uniqueIds = new LinkedHashSet<>();
        for (String rawId : rawIds) {
            if (rawId == null || rawId.isBlank() || !uniqueIds.add(rawId.trim()))
                throw new IllegalArgumentException("PO selection contains an empty or duplicate PO.");
        }
        String activeFactory = selectedFactoryScope.requested(AppConstants.Buyer.LULULEMON);
        String factory = null;
        // Validate all IDs, ownership, Factory permissions and duplicates before any write.
        for (String poId : uniqueIds) {
            PurchaseOrder po = pos.findByIdAndOrderIdAndBuyerCode(poId, orderId, AppConstants.Buyer.LULULEMON)
                    .orElseThrow(() -> new IllegalArgumentException("PO does not belong to this Order: " + poId));
            String candidateFactory = po.getFactoryCode();
            if (candidateFactory == null || candidateFactory.isBlank())
                throw new IllegalArgumentException("PO " + po.getPoNumber() + " has no assigned Factory.");
            candidateFactory = candidateFactory.trim().toUpperCase(Locale.ROOT);
            factories.requireActive(AppConstants.Buyer.LULULEMON, candidateFactory);
            factoryAccess.assertAssignedFactoryAccess(candidateFactory);
            if (activeFactory != null && !activeFactory.equalsIgnoreCase(candidateFactory))
                throw new AccessDeniedException("PO is not in the selected Factory.");
            if (factory == null) factory = candidateFactory;
            else if (!factory.equals(candidateFactory))
                throw new IllegalArgumentException("One Shipping Date item may contain POs from one Factory only.");
            if (!dates(po).isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "PO " + po.getPoNumber() + " is already assigned to a Shipping Schedule in this Order. "
                        + "Remove it from the existing Schedule before assigning it again.");
        }
        // A separate Create grant is required for NEW dates. Shipping Scheduler continues
        // to control adding PO(s) to an EXISTING date; neither grant implies the other.
        boolean existingSchedule = factoryPos(orderId, factory).stream()
                .anyMatch(po -> dates(po).contains(request.shippingDate()));
        if (existingSchedule ? !accessControl.canManageShipping() : !accessControl.canCreateShippingSchedule()) {
            throw new AccessDeniedException(existingSchedule
                    ? "Shipping Scheduler permission is required to add POs to an existing date."
                    : "Create Shipping Schedule permission is required to create a new date.");
        }
        assertEditable(orderId, factory, request.shippingDate());
        String actor = RequestActor.current();
        LocalDateTime now = LocalDateTime.now();
        Query eligible = Query.query(new Criteria().andOperator(
                Criteria.where("_id").in(uniqueIds),
                Criteria.where("orderId").is(orderId),
                Criteria.where("buyerCode").is(AppConstants.Buyer.LULULEMON),
                Criteria.where("factoryCode").is(factory),
                // Atomic guard against another browser/server assigning this PO to ANY day.
                Criteria.where("plannedShippingDate").is(null),
                new Criteria().orOperator(
                        Criteria.where("shippingDates").exists(false),
                        Criteria.where("shippingDates").is(null),
                        Criteria.where("shippingDates").size(0))
        ));
        Update fields = new Update()
                .addToSet("shippingDates", request.shippingDate())
                .set("updatedBy", actor).set("updatedAt", now);
        long changed = mongo.updateMulti(eligible, fields, PurchaseOrder.class).getModifiedCount();
        if (changed != uniqueIds.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Another request changed the selected POs. Refresh the Shipping Days before retrying.");
        }
        // Keep the legacy plannedShippingDate display/filter in sync with the
        // single Shipping Date. Existing historical multi-date POs remain readable.
        Query first = Query.query(new Criteria().andOperator(
                Criteria.where("_id").in(uniqueIds), Criteria.where("orderId").is(orderId),
                Criteria.where("buyerCode").is(AppConstants.Buyer.LULULEMON),
                Criteria.where("factoryCode").is(factory), Criteria.where("plannedShippingDate").is(null)));
        mongo.updateMulti(first, new Update().set("plannedShippingDate", request.shippingDate())
                .set("shippingScheduledBy", actor).set("shippingScheduledAt", now), PurchaseOrder.class);
        audit.logForFactory(actor, AppConstants.AuditAction.PO_SHIPPING_PLAN_CREATE,
                AppConstants.AuditResource.PO_SHIPPING_PLAN, orderId,
                "Added " + changed + " PO(s) to Shipping Date " + request.shippingDate() + " for Factory " + factory,
                AppConstants.Buyer.LULULEMON, factory);
        return Map.of("shippingDate", request.shippingDate().toString(), "factoryCode", factory,
                "scheduledCount", changed);
    }

    /** Remove just this PO from this date. Never delete the source PO or cartons. */
    public synchronized ShippingDayDetail removePo(String buyer, String orderId, String factoryCode,
                                                    LocalDate day, String poId) {
        requireOrder(buyer, orderId);
        String factory = selectedFactory(factoryCode);
        PurchaseOrder po = pos.findByIdAndOrderIdAndBuyerCode(poId, orderId, AppConstants.Buyer.LULULEMON)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PO not found."));
        if (!factory.equalsIgnoreCase(po.getFactoryCode())) throw new AccessDeniedException("PO belongs to another Factory.");
        assertEditable(orderId, factory, day);
        SortedSet<LocalDate> remaining = dates(po);
        if (!remaining.remove(day)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PO is not in this Shipping Date.");
        LocalDate newFirst = remaining.isEmpty() ? null : remaining.first();
        String actor = RequestActor.current();
        LocalDateTime now = LocalDateTime.now();
        Update update = new Update().set("shippingDates", new ArrayList<>(remaining))
                .set("plannedShippingDate", newFirst).set("updatedBy", actor).set("updatedAt", now);
        if (remaining.isEmpty()) update.unset("shippingScheduledBy").unset("shippingScheduledAt");
        long matched = mongo.updateFirst(Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(poId), Criteria.where("orderId").is(orderId),
                Criteria.where("buyerCode").is(AppConstants.Buyer.LULULEMON),
                Criteria.where("factoryCode").is(factory),
                // Avoid overwriting another tab's assignment made after this PO was read.
                Criteria.where("updatedAt").is(po.getUpdatedAt()))), update, PurchaseOrder.class).getMatchedCount();
        if (matched != 1) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "PO changed while editing this Shipping Date. Refresh and try again.");
        audit.logForFactory(actor, AppConstants.AuditAction.PO_SHIPPING_PLAN_REMOVE,
                AppConstants.AuditResource.PO_SHIPPING_PLAN, orderId,
                "Removed PO " + po.getPoNumber() + " from Shipping Date " + day + " for Factory " + factory,
                AppConstants.Buyer.LULULEMON, factory);
        // The date item disappears automatically if the final PO has been removed.
        List<ShippingPo> rows = factoryPos(orderId, factory).stream()
                .filter(row -> dates(row).contains(day))
                .map(row -> new ShippingPo(row.getId(), row.getPoNumber(), row.getMasterPo(),
                        row.getStyleNumber(), row.getSku(), row.getDestination(), row.getTotalQty(),
                        row.getPlannedCartons(), row.getPackingPlan(), row.getSalesOrderPts()))
                .toList();
        if (rows.isEmpty()) deleteSubmission(orderId, factory, day);
        return shippingDayDetail(orderId, factory, day, rows);
    }

    /** Delete a whole Shipping Date item, not source POs/cartons.
     * Submitted (including automatically submitted) dates require authorized Unsubmit first.
     */
    public synchronized Map<String, Object> deleteDay(String buyer, String orderId,
                                                      String factoryCode, LocalDate day) {
        requireOrder(buyer, orderId);
        String factory = selectedFactory(factoryCode);
        if (day == null) throw new IllegalArgumentException("Select a valid Shipping Date.");
        assertEditable(orderId, factory, day);
        List<PurchaseOrder> members = factoryPos(orderId, factory).stream()
                .filter(po -> dates(po).contains(day)).toList();
        if (members.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipping Schedule not found.");
        }
        String actor = RequestActor.current();
        LocalDateTime now = LocalDateTime.now();
        // Keep any other Shipping Dates on each PO. No PO or carton document is deleted.
        for (PurchaseOrder po : members) {
            SortedSet<LocalDate> remaining = dates(po);
            remaining.remove(day);
            Update update = new Update()
                    .set("shippingDates", new ArrayList<>(remaining))
                    .set("plannedShippingDate", remaining.isEmpty() ? null : remaining.first())
                    .set("updatedBy", actor).set("updatedAt", now);
            if (remaining.isEmpty()) update.unset("shippingScheduledBy").unset("shippingScheduledAt");
            long matched = mongo.updateFirst(Query.query(new Criteria().andOperator(
                    Criteria.where("_id").is(po.getId()),
                    Criteria.where("orderId").is(orderId),
                    Criteria.where("buyerCode").is(AppConstants.Buyer.LULULEMON),
                    Criteria.where("factoryCode").is(factory),
                    Criteria.where("updatedAt").is(po.getUpdatedAt()))), update, PurchaseOrder.class)
                    .getMatchedCount();
            if (matched != 1) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A PO changed while deleting this Shipping Schedule. Refresh and verify the schedule before retrying.");
        }
        deleteSubmission(orderId, factory, day);
        audit.logForFactory(actor, AppConstants.AuditAction.PO_SHIPPING_PLAN_REMOVE,
                AppConstants.AuditResource.PO_SHIPPING_PLAN, orderId,
                "Deleted Shipping Date " + day + " for Factory " + factory
                        + " (removed " + members.size() + " PO memberships; source POs kept)",
                AppConstants.Buyer.LULULEMON, factory);
        return Map.of("shippingDate", day.toString(), "factoryCode", factory, "removedCount", members.size());
    }

    /** Server-side XLSX for one Shipping Date; explicit, ordered, whitelisted columns. */
    public byte[] exportExcel(String buyer, String orderId, String factoryCode, LocalDate day,
                              List<String> requestedColumns) {
        ShippingDayDetail item = detail(buyer, orderId, factoryCode, day);
        List<String> columns = PoShippingExportColumns.resolve(requestedColumns);
        PackingOrder parent = orders.getEntity(AppConstants.Buyer.LULULEMON, orderId);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Shipping POs");
            Row titleRow = sheet.createRow(0);
            Cell title = titleRow.createCell(0);
            title.setCellValue("Shipping Date " + item.shippingDate() + " | Factory " + item.factoryCode());
            // Styling only: keep the existing title, columns, row order, and cell values intact.
            CellStyle titleStyle = workbook.createCellStyle();
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setColor(IndexedColors.DARK_GREEN.getIndex());
            titleStyle.setFont(titleFont);
            title.setCellStyle(titleStyle);

            // Soft light-green column headers; all table cells have visible thin grid borders.
            XSSFCellStyle headingStyle = (XSSFCellStyle) workbook.createCellStyle();
            headingStyle.setFillForegroundColor(new XSSFColor(new Color(223, 241, 228), null)); // #DFF1E4
            headingStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font headingFont = workbook.createFont();
            headingFont.setBold(true);
            headingFont.setColor(IndexedColors.DARK_GREEN.getIndex());
            headingStyle.setFont(headingFont);
            applyTableBorders(headingStyle);

            CellStyle bodyStyle = workbook.createCellStyle();
            applyTableBorders(bodyStyle);

            Row headings = sheet.createRow(2);
            headings.setHeightInPoints(23);
            for (int col = 0; col < columns.size(); col++) {
                Cell cell = headings.createCell(col);
                cell.setCellValue(PoShippingExportColumns.label(columns.get(col)));
                cell.setCellStyle(headingStyle);
            }
            Map<String, PurchaseOrder> byId = factoryPos(orderId, item.factoryCode()).stream()
                    .collect(Collectors.toMap(PurchaseOrder::getId, po -> po, (first, second) -> first));
            int rowNo = 0;
            for (ShippingPo selected : item.pos()) {
                PurchaseOrder po = byId.get(selected.id());
                if (po == null) continue;
                Row row = sheet.createRow(++rowNo + 2);
                for (int col = 0; col < columns.size(); col++) {
                    Object field = PoShippingExportColumns.cell(columns.get(col), po, day, parent.getOrderName(), rowNo);
                    set(row, col, field, bodyStyle);
                }
            }
            for (int col = 0; col < columns.size(); col++) {
                String label = PoShippingExportColumns.label(columns.get(col));
                sheet.setColumnWidth(col, Math.min(40, Math.max(13, label.length() + 5)) * 256);
            }
            sheet.createFreezePane(0, 3);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to export Shipping Date Excel.", e);
        }
    }

    /** Keep existing internal callers compatible with the new 12-column default. */
    public byte[] exportExcel(String buyer, String orderId, String factoryCode, LocalDate day) {
        return exportExcel(buyer, orderId, factoryCode, day, null);
    }

    private static void applyTableBorders(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        short lineColor = IndexedColors.GREY_40_PERCENT.getIndex();
        style.setTopBorderColor(lineColor);
        style.setBottomBorderColor(lineColor);
        style.setLeftBorderColor(lineColor);
        style.setRightBorderColor(lineColor);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
    }

    private static void set(Row row, int column, Object value, CellStyle style) {
        Cell cell = row.createCell(column);
        if (value instanceof Number number) cell.setCellValue(number.doubleValue());
        else cell.setCellValue(value == null ? "" : String.valueOf(value));
        cell.setCellStyle(style);
    }
}
