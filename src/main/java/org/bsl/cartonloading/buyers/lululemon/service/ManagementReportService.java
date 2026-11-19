package org.bsl.cartonloading.buyers.lululemon.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.shared.orders.model.OrderDocument;
import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.model.RfidScanSession;
import org.bsl.cartonloading.buyers.lululemon.model.WeighingResult;
import org.bsl.cartonloading.buyers.lululemon.model.ScanEvent;
import org.springframework.data.domain.Sort;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/** Read-only reporting over imported LULULEMON POs; Shipping Schedule is optional. Never changes a PO/carton. */
@Service
public class ManagementReportService {
    private static final String BUYER = AppConstants.Buyer.LULULEMON;
    private static final String SUBMISSIONS = AppConstants.Collection.PO_SHIPPING_DAY_SUBMISSIONS;
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private final MongoTemplate mongo;
    private final BuyerFactoryAccessService factoryAccess;

    public ManagementReportService(MongoTemplate mongo, BuyerFactoryAccessService factoryAccess) {
        this.mongo = mongo;
        this.factoryAccess = factoryAccess;
    }

    public record Filters(LocalDate from, LocalDate to, String factory, String shippingDate, String orderId,
                          String status, String dateBasis) {
        /** Compatibility for callers/tests that relied on shipping-only reporting. */
        public Filters(LocalDate from, LocalDate to, String factory, String shippingDate, String orderId, String status) {
            this(from, to, factory, shippingDate, orderId, status, "SHIPPING");
        }
    }
    public record Totals(long orders, long factories, long shippingDays, long poAssignments,
                         long uniquePos, long scheduledPos, long unscheduledPos,
                         long plannedCartons, long generatedCartons,
                         long rfidPass, long rfidFailed, long weightPass, long weightFailed,
                         long finalPass, long finalFailed, long pending) {}
    public record PoRow(String shippingDate, String shippingStatus, String factoryCode, String orderId,
                        String orderName, String poId, String poNumber, String masterPo, String style,
                        String sku, String color, String destination, String channel, String dcCode,
                        int plannedCartons, int generatedCartons, int rfidPass, int rfidFailed,
                        int weightPass, int weightFailed, int finalPass, int finalFailed, int pending,
                        String status) {}
    public record FactoryRow(String factoryCode, long pos, long plannedCartons, long finalPass, long finalFailed, long pending) {}
    public record ShippingRow(String shippingDate, String factoryCode, String orderId,
                              String orderName, String shippingStatus, long pos, long plannedCartons, long finalPass, long finalFailed, long pending) {}
    public record OrderOption(String orderId, String orderName) {}
    public record Options(List<String> factories, List<String> shippingDates, List<OrderOption> orders) {}
    public record Report(Filters filters, Totals totals, List<FactoryRow> factories,
                         List<ShippingRow> shippingDays, Options options, List<PoRow> pos,
                         long totalRows, int page, int size) {}
    // Compact chart-only view; uses the same authorized dataset as Management Reports.
    public record ShippingTrendPoint(String shippingDate, long plannedCartons, long finalPass, long finalFailed) {}
    /** Daily snapshots of current inspection outcomes, grouped by the saved check timestamps.
     * Each physical carton is counted at most once per stage (no double-counting re-scans).
     * These dates do not require a Shipping Schedule.
     */
    public record ProductionTrendPoint(String date, long rfidPass, long rfidFailed,
                                       long weightPass, long weightFailed,
                                       long finalPass, long finalFailed) {}
    public record FactoryPerformancePoint(String factoryCode, long plannedCartons, long finalPass, long finalFailed) {}
    public record QcStatus(long finalPass, long finalFailed, long pending) {}
    public record DashboardOverview(long orders, long pos, long plannedCartons, long generatedCartons,
                                    long scheduledPos, long unscheduledPos, long rfidPass, long rfidFailed,
                                    long weightPass, long weightFailed, long finalPass, long finalFailed, long pending) {}
    public record DashboardSummary(List<ShippingTrendPoint> shippingTrend,
                                   List<ProductionTrendPoint> productionTrend,
                                   List<FactoryPerformancePoint> factoryPerformance,
                                   QcStatus qcStatus, List<String> availableFactories,
                                   DashboardOverview overview) {}
    public record CartonRow(String cartonId, Integer cartonNo, String sscc18, Integer plannedQty,
                            Integer scannedQty, String rfidStatus, String weightStatus,
                            String finalStatus, String expectedWeightKg, String actualWeightKg,
                            String rfidVerifiedAt, String weighedAt, String weighedBy, String updatedAt) {}
    public record PoDetail(PoRow po, List<CartonRow> cartons, long totalCartons, int page, int size) {}
    public record CartonActivity(List<RfidScanSession> rfidSessions, List<WeighingResult> weightEvents,
                                 List<ScanEvent> events) {}

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String code(String value) { return clean(value).toUpperCase(Locale.ROOT); }
    private static int positive(Integer value) { return Math.max(0, value == null ? 0 : value); }
    private static String txt(Object value) { return value == null ? "" : value.toString(); }

    private void authorize() {
        var user = factoryAccess.currentUser();
        if (!user.isEnabled() || !user.canLululemonViewManagementReports())
            throw new AccessDeniedException("LULULEMON report access denied");
    }

    private static boolean canReportFactory(org.bsl.cartonloading.model.User user, String factory) {
        String normalized = code(factory);
        return !normalized.isEmpty() && (user.canLululemonReportAllFactories()
                || (user.canLululemonReportAssignedFactories()
                    && user.getFactoryPermissions().contains(normalized)));
    }

    private Filters checked(Filters filters) {
        authorize();
        if (filters == null || filters.from() == null || filters.to() == null || filters.from().isAfter(filters.to()))
            throw new IllegalArgumentException("Select a valid Date From / Date To range.");
        if (filters.from().plusYears(1).isBefore(filters.to()))
            throw new IllegalArgumentException("Select a date range of at most one year.");
        // Reports are independent from the workstation ACTIVE_FACTORY header;
        // the explicit report Factory filter is authorization-checked below.
        String requestFactory = code(filters.factory());
        if (!requestFactory.isEmpty() && !("UNASSIGNED".equals(requestFactory)
                && factoryAccess.currentUser().canLululemonReportAllFactories())
                && !canReportFactory(factoryAccess.currentUser(), requestFactory))
            throw new AccessDeniedException("Factory report access denied");
        String basis = code(filters.dateBasis());
        if (basis.isEmpty()) basis = "SHIPPING";
        if (!Set.of("ALL", "IMPORTED", "SHIPPING").contains(basis))
            throw new IllegalArgumentException("Unsupported report date basis.");
        String day = clean(filters.shippingDate());
        if (!day.isEmpty()) {
            try { LocalDate.parse(day); }
            catch (java.time.format.DateTimeParseException ex) {
                throw new IllegalArgumentException("Invalid Shipping Date filter.");
            }
        }
        return new Filters(filters.from(), filters.to(), requestFactory, day,
                clean(filters.orderId()), code(filters.status()), basis);
    }

    private static SortedSet<LocalDate> membership(PurchaseOrder po) {
        SortedSet<LocalDate> days = new TreeSet<>();
        if (po.getShippingDates() != null) for (LocalDate day : po.getShippingDates()) if (day != null) days.add(day);
        if (po.getPlannedShippingDate() != null) days.add(po.getPlannedShippingDate());
        return days;
    }

    static String cartonFinalStatus(Carton carton) {
        boolean rfidDone = "PASS".equalsIgnoreCase(clean(carton.getRfidStatus())) || "FAILED".equalsIgnoreCase(clean(carton.getRfidStatus()));
        boolean weightPass = "PASS".equalsIgnoreCase(clean(carton.getWeightStatus()));
        boolean weightDone = weightPass || "FAILED".equalsIgnoreCase(clean(carton.getWeightStatus()))
                || "HOLD".equalsIgnoreCase(clean(carton.getWeightStatus()));
        if (!rfidDone || !weightDone) return "PENDING";
        return "PASS".equalsIgnoreCase(clean(carton.getRfidStatus())) && weightPass ? "PASS" : "FAILED";
    }

    private String submission(String orderId, String factory, LocalDate day, Map<String, Document> submissions) {
        String id = BUYER + "|" + orderId + "|" + factory + "|" + day;
        Document state = submissions.get(id);
        String saved = state == null ? "" : clean(state.getString("state"));
        if ("REOPENED".equalsIgnoreCase(saved)) return "REOPENED";
        if ("SUBMITTED".equalsIgnoreCase(saved) || !day.isAfter(LocalDate.now(VIETNAM))) return "SUBMITTED";
        return "PLANNED";
    }

    private record Data(Filters filters, List<PoRow> rows, List<PoRow> shippingRows,
                        Options options, Map<String, List<Carton>> cartons) { }

    /** Date modes: ALL (no date restriction), IMPORTED (PO/Order creation day),
     * SHIPPING (one row per shipping-day membership). Each overview PO is counted once.
     */
    private Data load(Filters original) {
        Filters f = checked(original);
        Criteria criteria = Criteria.where("buyerCode").is(BUYER);
        if ("SHIPPING".equals(f.dateBasis())) {
            Criteria date = new Criteria().orOperator(
                    Criteria.where("shippingDates").gte(f.from()).lte(f.to()),
                    Criteria.where("plannedShippingDate").gte(f.from()).lte(f.to()));
            criteria = new Criteria().andOperator(criteria, date);
        }
        if (!f.factory().isEmpty()) criteria = new Criteria().andOperator(criteria,
                "UNASSIGNED".equals(f.factory())
                        ? new Criteria().orOperator(Criteria.where("factoryCode").is(null), Criteria.where("factoryCode").is(""))
                        : Criteria.where("factoryCode").is(f.factory()));
        // Report permission is independent of ACTIVE_FACTORY and cannot be bypassed by ADMIN.
        var reportUser = factoryAccess.currentUser();
        Set<String> allowed = reportUser.canLululemonReportAllFactories() ? null :
                reportUser.getFactoryPermissions().stream().map(ManagementReportService::code).collect(Collectors.toSet());
        if (allowed != null) criteria = new Criteria().andOperator(criteria, Criteria.where("factoryCode").in(allowed));

        List<PurchaseOrder> purchaseOrders = new ArrayList<>(mongo.find(Query.query(criteria), PurchaseOrder.class));
        purchaseOrders.removeIf(p -> clean(p.getOrderId()).isEmpty() || clean(p.getId()).isEmpty()
                || (allowed != null && !allowed.contains(code(p.getFactoryCode())))
                || (!f.factory().isEmpty() && !f.factory().equals(
                        code(p.getFactoryCode()).isEmpty() ? "UNASSIGNED" : code(p.getFactoryCode())))
                || (allowed != null && clean(p.getFactoryCode()).isEmpty()));

        Set<String> orderIds = purchaseOrders.stream().map(PurchaseOrder::getOrderId).collect(Collectors.toSet());
        Map<String, OrderDocument> orders = orderIds.isEmpty() ? Map.of() : mongo.find(Query.query(
                new Criteria().andOperator(Criteria.where("buyerCode").is(BUYER), Criteria.where("_id").in(orderIds))),
                OrderDocument.class).stream().collect(Collectors.toMap(OrderDocument::getId, o -> o, (a, b) -> a));
        // Never expose orphan POs or POs under a deleted parent.
        purchaseOrders.removeIf(p -> !orders.containsKey(p.getOrderId()));
        if ("IMPORTED".equals(f.dateBasis())) purchaseOrders.removeIf(p -> {
            LocalDateTime created = p.getCreatedAt() != null ? p.getCreatedAt()
                    : orders.get(p.getOrderId()).getCreatedAt();
            if (created == null) return true; // do not treat Order Date as a verified import date
            LocalDate day = created.toLocalDate();
            return day.isBefore(f.from()) || day.isAfter(f.to());
        });

        Set<String> poIds = purchaseOrders.stream().map(PurchaseOrder::getId).collect(Collectors.toSet());
        Map<String, List<Carton>> cartons = new HashMap<>();
        if (!poIds.isEmpty()) {
            Query cartonQuery = Query.query(new Criteria().andOperator(
                    Criteria.where("buyerCode").is(BUYER), Criteria.where("poId").in(poIds)));
            for (Carton c : mongo.find(cartonQuery, Carton.class)) {
                if (poIds.contains(c.getPoId()) && (allowed == null || allowed.contains(code(c.getFactoryCode()))))
                    cartons.computeIfAbsent(c.getPoId(), ignored -> new ArrayList<>()).add(c);
            }
        }
        Criteria submissionDates = "SHIPPING".equals(f.dateBasis())
                ? Criteria.where("shippingDate").gte(f.from().toString()).lte(f.to().toString())
                : Criteria.where("orderId").in(purchaseOrders.stream().map(PurchaseOrder::getOrderId).collect(Collectors.toSet()));
        Map<String, Document> submissions = poIds.isEmpty() ? Map.of() : mongo.find(Query.query(new Criteria().andOperator(
                Criteria.where("buyerCode").is(BUYER), submissionDates)),
                Document.class, SUBMISSIONS).stream()
                .filter(d -> d.getString("_id") != null)
                .collect(Collectors.toMap(d -> d.getString("_id"), d -> d, (a, b) -> a));

        List<PoRow> overview = new ArrayList<>();
        List<PoRow> scheduled = new ArrayList<>();
        SortedSet<String> availableDays = new TreeSet<>(Comparator.reverseOrder());
        for (PurchaseOrder p : purchaseOrders) {
            String fac = code(p.getFactoryCode());
            // Unassigned PO is visible only with an ALL-factories report grant.
            if (fac.isEmpty()) fac = "UNASSIGNED";
            final String scopedFactory = fac;
            List<Carton> poCartons = cartons.getOrDefault(p.getId(), List.of()).stream()
                    .filter(c -> Objects.equals(p.getOrderId(), c.getOrderId())
                            && scopedFactory.equals(code(c.getFactoryCode()).isEmpty() ? "UNASSIGNED" : code(c.getFactoryCode())))
                    .toList();
            int rp = 0, rf = 0, wp = 0, wf = 0, fp = 0, ff = 0;
            for (Carton c : poCartons) {
                if ("PASS".equalsIgnoreCase(clean(c.getRfidStatus()))) rp++;
                if ("FAILED".equalsIgnoreCase(clean(c.getRfidStatus()))) rf++;
                if ("PASS".equalsIgnoreCase(clean(c.getWeightStatus()))) wp++;
                if ("FAILED".equalsIgnoreCase(clean(c.getWeightStatus())) || "HOLD".equalsIgnoreCase(clean(c.getWeightStatus()))) wf++;
                if ("PASS".equals(cartonFinalStatus(c))) fp++;
                if ("FAILED".equals(cartonFinalStatus(c))) ff++;
            }
            int planned = positive(p.getPlannedCartons());
            int pending = Math.max(0, Math.max(planned, poCartons.size()) - fp - ff);
            String state = ff > 0 ? "ISSUES" : planned > 0 && fp >= planned ? "COMPLETED" : "IN_PROGRESS";
            OrderDocument parent = orders.get(p.getOrderId());
            String name = clean(parent.getOrderName()).isEmpty() ? parent.getId() : parent.getOrderName();
            SortedSet<LocalDate> dates = membership(p);
            for (LocalDate day : dates) {
                if ("SHIPPING".equals(f.dateBasis())
                        && (day.isBefore(f.from()) || day.isAfter(f.to()))) continue;
                String shippingDate = day.toString();
                availableDays.add(shippingDate);
                scheduled.add(new PoRow(shippingDate, submission(p.getOrderId(), fac, day, submissions), fac,
                        p.getOrderId(), name, p.getId(), p.getPoNumber(), p.getMasterPo(),
                        p.getStyle(), p.getSku(), p.getColor(), p.getDestination(), p.getChannel(), p.getDcCode(),
                        planned, poCartons.size(), rp, rf, wp, wf, fp, ff, pending, state));
            }
            // Even a PO with no Shipping Schedule is present in the default overview.
            LocalDate preferred = !f.shippingDate().isEmpty() && dates.contains(LocalDate.parse(f.shippingDate()))
                    ? LocalDate.parse(f.shippingDate()) : dates.isEmpty() ? null : dates.first();
            overview.add(new PoRow(preferred == null ? "" : preferred.toString(),
                    preferred == null ? "UNSCHEDULED" : submission(p.getOrderId(), fac, preferred, submissions),
                    fac, p.getOrderId(), name, p.getId(), p.getPoNumber(), p.getMasterPo(),
                    p.getStyle(), p.getSku(), p.getColor(), p.getDestination(), p.getChannel(), p.getDcCode(),
                    planned, poCartons.size(), rp, rf, wp, wf, fp, ff, pending, state));
        }
        // Choices are computed before narrow Order/Status filters; options remain usable
        // when the current selection has no matching results.
        List<PoRow> optionsSource = "SHIPPING".equals(f.dateBasis()) ? scheduled : overview;
        Options options = new Options(optionsSource.stream().map(PoRow::factoryCode).distinct().sorted().toList(),
                new ArrayList<>(availableDays),
                optionsSource.stream().collect(Collectors.toMap(PoRow::orderId,
                        r -> new OrderOption(r.orderId(), r.orderName()), (a, b) -> a))
                        .values().stream().sorted(Comparator.comparing(OrderOption::orderName)).toList());

        scheduled.removeIf(r -> !rowMatches(r, f));
        overview.removeIf(r -> !rowMatches(r, f));
        List<PoRow> all = "SHIPPING".equals(f.dateBasis()) ? scheduled : overview;
        Comparator<PoRow> sorter = Comparator.comparing(PoRow::shippingDate).reversed()
                .thenComparing(PoRow::factoryCode).thenComparing(PoRow::orderName)
                .thenComparing(PoRow::poNumber, Comparator.nullsFirst(String::compareTo));
        all.sort(sorter);
        if (all != scheduled) scheduled.sort(sorter);
        return new Data(f, all, scheduled, options, cartons);
    }

    private static boolean rowMatches(PoRow r, Filters f) {
        return (f.shippingDate().isEmpty() || f.shippingDate().equals(r.shippingDate()))
                && (f.orderId().isEmpty() || f.orderId().equals(r.orderId()))
                && (f.status().isEmpty() || "ALL".equals(f.status()) || f.status().equals(r.status()));
    }

    private static List<PoRow> uniquePos(List<PoRow> rows) {
        return new ArrayList<>(rows.stream().collect(Collectors.toMap(PoRow::poId, r -> r, (a, b) -> a,
                LinkedHashMap::new)).values());
    }

    private static long sum(List<PoRow> rows, java.util.function.ToLongFunction<PoRow> getter) {
        return rows.stream().mapToLong(getter).sum();
    }
    private static Totals totals(List<PoRow> rows, List<PoRow> scheduled) {
        List<PoRow> unique = uniquePos(rows);
        return new Totals(unique.stream().map(PoRow::orderId).distinct().count(),
                unique.stream().map(PoRow::factoryCode).distinct().count(),
                scheduled.stream().map(r -> r.factoryCode() + "|" + r.shippingDate() + "|" + r.orderId())
                        .distinct().count(),
                scheduled.size(), unique.size(),
                unique.stream().filter(r -> !r.shippingDate().isEmpty()).count(),
                unique.stream().filter(r -> r.shippingDate().isEmpty()).count(),
                sum(unique, PoRow::plannedCartons), sum(unique, PoRow::generatedCartons),
                sum(unique, PoRow::rfidPass), sum(unique, PoRow::rfidFailed),
                sum(unique, PoRow::weightPass), sum(unique, PoRow::weightFailed),
                sum(unique, PoRow::finalPass), sum(unique, PoRow::finalFailed), sum(unique, PoRow::pending));
    }

    /** Lightweight filter options without loading QC cartons or submissions. */
    public Options dashboardOptions(Filters original) {
        Filters f = checked(original);
        var reportUser = factoryAccess.currentUser();
        Set<String> allowed = reportUser.canLululemonReportAllFactories() ? null :
                reportUser.getFactoryPermissions().stream().map(ManagementReportService::code).collect(Collectors.toSet());
        Criteria criteria = Criteria.where("buyerCode").is(BUYER);
        if (allowed != null) criteria = new Criteria().andOperator(criteria, Criteria.where("factoryCode").in(allowed));
        if ("SHIPPING".equals(f.dateBasis())) criteria = new Criteria().andOperator(criteria,
                new Criteria().orOperator(Criteria.where("shippingDates").gte(f.from()).lte(f.to()),
                        Criteria.where("plannedShippingDate").gte(f.from()).lte(f.to())));
        Query query = Query.query(criteria);
        query.fields().include("_id").include("orderId").include("factoryCode")
                .include("shippingDates").include("plannedShippingDate").include("createdAt");
        List<PurchaseOrder> all = new ArrayList<>(mongo.find(query, PurchaseOrder.class));
        all.removeIf(p -> clean(p.getId()).isEmpty() || clean(p.getOrderId()).isEmpty()
                || (allowed != null && !allowed.contains(code(p.getFactoryCode()))));
        Set<String> ids = all.stream().map(PurchaseOrder::getOrderId).collect(Collectors.toSet());
        Map<String, OrderDocument> orders = ids.isEmpty() ? Map.of() : mongo.find(Query.query(
                new Criteria().andOperator(Criteria.where("buyerCode").is(BUYER), Criteria.where("_id").in(ids))),
                OrderDocument.class).stream().collect(Collectors.toMap(OrderDocument::getId, o -> o, (a, b) -> a));
        all.removeIf(p -> !orders.containsKey(p.getOrderId()));
        if ("IMPORTED".equals(f.dateBasis())) all.removeIf(p -> {
            LocalDateTime created = p.getCreatedAt() != null ? p.getCreatedAt()
                    : orders.get(p.getOrderId()).getCreatedAt();
            return created == null || created.toLocalDate().isBefore(f.from()) || created.toLocalDate().isAfter(f.to());
        });
        if ("SHIPPING".equals(f.dateBasis())) all.removeIf(p -> membership(p).stream().noneMatch(
                day -> !day.isBefore(f.from()) && !day.isAfter(f.to())));
        List<String> factories = all.stream().map(p -> code(p.getFactoryCode()).isEmpty() ? "UNASSIGNED" : code(p.getFactoryCode()))
                .distinct().sorted().toList();
        List<PurchaseOrder> scoped = all.stream()
                .filter(p -> f.factory().isEmpty() || f.factory().equals(
                        code(p.getFactoryCode()).isEmpty() ? "UNASSIGNED" : code(p.getFactoryCode()))).toList();
        List<String> dates = scoped.stream().flatMap(p -> membership(p).stream())
                .filter(day -> !"SHIPPING".equals(f.dateBasis()) || (!day.isBefore(f.from()) && !day.isAfter(f.to())))
                .map(LocalDate::toString).distinct().sorted(Comparator.reverseOrder()).toList();
        List<OrderOption> orderOptions = scoped.stream().map(PurchaseOrder::getOrderId).distinct()
                .map(id -> new OrderOption(id, clean(orders.get(id).getOrderName()).isEmpty()
                        ? id : orders.get(id).getOrderName()))
                .sorted(Comparator.comparing(OrderOption::orderName)).toList();
        return new Options(factories, dates, orderOptions);
    }

    /** Count the *current* carton outcome on the date of its saved RFID/Weight check.
     * This is a snapshot grouped by inspection timestamps, not an immutable event audit.
     * Older carton rows without check timestamps are left out of the daily chart;
     * the UI still visualizes those rows from the aggregate overview instead.
     */
    private static List<ProductionTrendPoint> inspectionTrend(Data data, List<PoRow> unique) {
        Map<LocalDate, long[]> daily = new TreeMap<>();
        Set<String> seenCartons = new HashSet<>();
        for (PoRow row : unique) {
            for (Carton carton : data.cartons().getOrDefault(row.poId(), List.of())) {
                if (clean(carton.getId()).isEmpty()
                        || !Objects.equals(carton.getOrderId(), row.orderId())
                        || !row.factoryCode().equals(code(carton.getFactoryCode()).isEmpty()
                            ? "UNASSIGNED" : code(carton.getFactoryCode()))
                        || !seenCartons.add(carton.getId())) continue;
                boolean rfidPass = "PASS".equalsIgnoreCase(clean(carton.getRfidStatus()));
                boolean rfidFail = "FAILED".equalsIgnoreCase(clean(carton.getRfidStatus()));
                boolean weightPass = "PASS".equalsIgnoreCase(clean(carton.getWeightStatus()));
                boolean weightFail = "FAILED".equalsIgnoreCase(clean(carton.getWeightStatus()))
                        || "HOLD".equalsIgnoreCase(clean(carton.getWeightStatus()));
                LocalDateTime rfidAt = carton.getRfidVerifiedAt();
                LocalDateTime weightAt = carton.getWeighedAt();
                if (rfidAt != null && (rfidPass || rfidFail))
                    incrementDaily(daily, rfidAt.toLocalDate(), rfidPass ? 0 : 1, data.filters());
                if (weightAt != null && (weightPass || weightFail))
                    incrementDaily(daily, weightAt.toLocalDate(), weightPass ? 2 : 3, data.filters());
                // A completed Final requires BOTH checks; use the later saved inspection timestamp.
                // Do not assign a made-up date if either timestamp is missing.
                if (rfidAt != null && weightAt != null && (rfidPass || rfidFail) && (weightPass || weightFail)) {
                    LocalDateTime last = rfidAt.isAfter(weightAt) ? rfidAt : weightAt;
                    incrementDaily(daily, last.toLocalDate(), rfidPass && weightPass ? 4 : 5, data.filters());
                }
            }
        }
        return daily.entrySet().stream().map(entry -> {
            long[] counts = entry.getValue();
            return new ProductionTrendPoint(entry.getKey().toString(), counts[0], counts[1],
                    counts[2], counts[3], counts[4], counts[5]);
        }).toList();
    }

    private static void incrementDaily(Map<LocalDate, long[]> daily, LocalDate day,
                                       int index, Filters filters) {
        if (day.isBefore(filters.from()) || day.isAfter(filters.to())) return;
        daily.computeIfAbsent(day, ignored -> new long[6])[index]++;
    }

    /** Production / QC uses unique POs. Shipping trend is strictly date membership. */
    public DashboardSummary dashboardSummary(Filters filters) {
        Data data = load(filters);
        List<PoRow> rows = uniquePos(data.rows());
        // In All Dates / Imported Date mode, Orders/QC are independent of schedule;
        // the Shipping Trend chart still uses the requested visible date window.
        List<ShippingTrendPoint> trend = data.shippingRows().stream()
                .filter(r -> r.shippingDate().compareTo(data.filters().from().toString()) >= 0
                        && r.shippingDate().compareTo(data.filters().to().toString()) <= 0)
                .collect(Collectors.groupingBy(PoRow::shippingDate, TreeMap::new, Collectors.toList()))
                .entrySet().stream().map(entry -> new ShippingTrendPoint(entry.getKey(),
                        sum(entry.getValue(), PoRow::plannedCartons),
                        sum(entry.getValue(), PoRow::finalPass),
                        sum(entry.getValue(), PoRow::finalFailed))).toList();
        List<FactoryPerformancePoint> factories = rows.stream()
                .collect(Collectors.groupingBy(PoRow::factoryCode, TreeMap::new, Collectors.toList()))
                .entrySet().stream().map(entry -> new FactoryPerformancePoint(entry.getKey(),
                        sum(entry.getValue(), PoRow::plannedCartons),
                        sum(entry.getValue(), PoRow::finalPass),
                        sum(entry.getValue(), PoRow::finalFailed))).toList();
        Totals totals = totals(data.rows(), data.shippingRows());
        DashboardOverview overview = new DashboardOverview(totals.orders(), totals.uniquePos(),
                totals.plannedCartons(), totals.generatedCartons(), totals.scheduledPos(),
                totals.unscheduledPos(), totals.rfidPass(), totals.rfidFailed(),
                totals.weightPass(), totals.weightFailed(), totals.finalPass(), totals.finalFailed(), totals.pending());
        return new DashboardSummary(trend, inspectionTrend(data, rows), factories,
                new QcStatus(totals.finalPass(), totals.finalFailed(), totals.pending()),
                data.options().factories(), overview);
    }

    public Report report(Filters filters, int page, int size) {
        Data data = load(filters);
        int perPage = Math.min(100, Math.max(1, size));
        int pageNo = Math.max(0, page);
        List<PoRow> rows = data.rows();
        Map<String, List<PoRow>> byFactory = rows.stream()
                .collect(Collectors.groupingBy(PoRow::factoryCode, TreeMap::new, Collectors.toList()));
        List<FactoryRow> factories = byFactory.entrySet().stream().map(e -> {
            List<PoRow> unique = uniquePos(e.getValue());
            return new FactoryRow(e.getKey(), unique.size(), sum(unique, PoRow::plannedCartons),
                    sum(unique, PoRow::finalPass), sum(unique, PoRow::finalFailed), sum(unique, PoRow::pending));
        }).toList();
        Map<String, List<PoRow>> byDay = data.shippingRows().stream().collect(Collectors.groupingBy(
                r -> r.shippingDate() + "|" + r.factoryCode() + "|" + r.orderId(), TreeMap::new, Collectors.toList()));
        List<ShippingRow> shippingDays = byDay.values().stream().map(list -> {
            PoRow first = list.get(0);
            return new ShippingRow(first.shippingDate(), first.factoryCode(), first.orderId(), first.orderName(),
                    first.shippingStatus(), list.size(), sum(list, PoRow::plannedCartons),
                    sum(list, PoRow::finalPass), sum(list, PoRow::finalFailed), sum(list, PoRow::pending));
        }).sorted(Comparator.comparing(ShippingRow::shippingDate).reversed()).toList();
        long start = (long) pageNo * perPage;
        List<PoRow> slice = start >= rows.size() ? List.of()
                : rows.subList((int) start, (int) Math.min(rows.size(), start + perPage));
        return new Report(data.filters(), totals(rows, data.shippingRows()), factories, shippingDays,
                data.options(), slice, rows.size(), pageNo, perPage);
    }

    private static CartonRow cartonRow(Carton c) {
        return new CartonRow(c.getId(), c.getCartonNo(), c.getSscc18(), c.getPlannedQty(), c.getScannedQty(),
                clean(c.getRfidStatus()).isEmpty() ? "NOT_SCANNED" : c.getRfidStatus(),
                clean(c.getWeightStatus()).isEmpty() ? "PENDING" : c.getWeightStatus(), cartonFinalStatus(c),
                txt(c.getExpectedWeightKg()), txt(c.getActualWeightKg()), txt(c.getRfidVerifiedAt()),
                txt(c.getWeighedAt()), c.getWeighedBy(), txt(c.getUpdatedAt()));
    }

    public PoDetail poDetail(Filters filters, String poId, String date, int page, int size) {
        Data data = load(filters);
        PoRow po = data.rows().stream().filter(r -> r.poId().equals(poId) && r.shippingDate().equals(date))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PO not found in this authorized report scope"));
        List<CartonRow> all = data.cartons().getOrDefault(poId, List.of()).stream()
                .filter(c -> Objects.equals(po.orderId(), c.getOrderId()) && po.factoryCode().equals(code(c.getFactoryCode())))
                .sorted(Comparator.comparing(Carton::getCartonNo, Comparator.nullsLast(Integer::compareTo)))
                .map(ManagementReportService::cartonRow).toList();
        int perPage = Math.min(100, Math.max(1, size));
        int pageNo = Math.max(0, page);
        long start = (long) pageNo * perPage;
        return new PoDetail(po, start >= all.size() ? List.of() : all.subList((int) start, (int) Math.min(all.size(), start + perPage)),
                all.size(), pageNo, perPage);
    }

    /** Any authorized carton (including incomplete ones) may show saved attempts, read-only. */
    public CartonActivity cartonHistory(Filters filters, String poId, String date, String cartonId) {
        Data data = load(filters);
        PoRow row = data.rows().stream().filter(p -> p.poId().equals(poId) && p.shippingDate().equals(date))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PO outside authorized report"));
        Carton carton = data.cartons().getOrDefault(poId, List.of()).stream()
                .filter(c -> Objects.equals(c.getId(), cartonId)
                        && Objects.equals(c.getOrderId(), row.orderId())
                        && row.factoryCode().equals(code(c.getFactoryCode())))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Carton outside authorized report"));
        Query rfid = Query.query(new Criteria().andOperator(Criteria.where("buyerCode").is(BUYER),
                Criteria.where("cartonId").is(carton.getId())))
                .with(Sort.by(Sort.Direction.DESC, "startedAt")).limit(1000);
        Query weight = Query.query(Criteria.where("cartonId").is(carton.getId()))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(1000);
        Query scan = Query.query(new Criteria().andOperator(Criteria.where("buyerCode").is(BUYER),
                Criteria.where("cartonId").is(carton.getId())))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(1000);
        return new CartonActivity(mongo.find(rfid, RfidScanSession.class),
                mongo.find(weight, WeighingResult.class), mongo.find(scan, ScanEvent.class));
    }

    /** Excel strings have a 32,767 character limit and cannot contain XML control characters.
     * Sanitize imported PO text instead of letting Apache POI abort the complete export.
     */
    private static String excelText(Object value) {
        if (value == null) return "";
        String input = value.toString();
        StringBuilder result = new StringBuilder(Math.min(input.length(), 32767));
        for (int i = 0; i < input.length() && result.length() < 32767; i++) {
            char ch = input.charAt(i);
            if (ch == '\t' || ch == '\n' || ch == '\r' || ch >= 0x20) {
                if (Character.isHighSurrogate(ch)) {
                    if (i + 1 < input.length() && Character.isLowSurrogate(input.charAt(i + 1))) {
                        if (result.length() + 2 > 32767) break;
                        result.append(ch).append(input.charAt(++i));
                    } else result.append(' ');
                } else if (Character.isLowSurrogate(ch) || ch == '\ufffe' || ch == '\uffff') {
                    result.append(' ');
                } else result.append(ch);
            } else result.append(' ');
        }
        return result.toString();
    }

    private static void append(Sheet sheet, Object... values) {
        // Every sheet created below is appended sequentially (no blank-index rows).
        Row row = sheet.createRow(sheet.getPhysicalNumberOfRows());
        for (int i = 0; i < values.length; i++) {
            Cell cell = row.createCell(i);
            Object value = values[i];
            if (value instanceof Number number) cell.setCellValue(number.doubleValue());
            else cell.setCellValue(excelText(value));
        }
    }

    private void authorizeExport() {
        if (!factoryAccess.currentUser().canLululemonExportManagementReports())
            throw new AccessDeniedException("Management Report Export permission required");
    }

    /** Management screen export: one summary sheet only; never bundle per-carton data. */
    public byte[] export(Filters filters) throws IOException {
        authorizeExport();
        Data data = load(filters);
        if (data.rows().size() > 20000)
            throw new IllegalArgumentException("Export has over 20,000 PO rows. Narrow the report date range.");
        return shippingOrderWorkbook(data);
    }

    /** A separate one-sheet carton export for the PO selected inside its read-only popup. */
    public byte[] exportPo(Filters filters, String poId, String date) throws IOException {
        authorizeExport();
        Data data = load(filters);
        PoRow po = data.rows().stream()
                .filter(row -> Objects.equals(poId, row.poId()) && Objects.equals(date, row.shippingDate()))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "PO not found in authorized report scope"));
        return cartonDetailsWorkbook(data, po);
    }

    /** Consistent one-sheet Excel layout with the applied scope above the filterable table. */
    private static void formatReportSheet(XSSFWorkbook book, Sheet sheet, int headerRow, int columns) {
        sheet.createFreezePane(0, headerRow + 1);
        if (sheet.getLastRowNum() > headerRow)
            sheet.setAutoFilter(new CellRangeAddress(headerRow, sheet.getLastRowNum(), 0, columns - 1));

        CellStyle headerStyle = book.createCellStyle();
        Font headerFont = book.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        Row header = sheet.getRow(headerRow);
        header.setHeightInPoints(26);
        for (Cell cell : header) cell.setCellStyle(headerStyle);
        for (int col = 0; col < columns; col++) {
            sheet.setColumnWidth(col, (col == 3 || col == 4 || col == 6) ? 6000 : 4600);
        }
        sheet.setColumnWidth(0, 5800);
    }

    private byte[] shippingOrderWorkbook(Data data) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Shipping & Order Report");
            append(sheet, "LULULEMON - Order & Shipping Report");
            append(sheet, "Date Basis", data.filters().dateBasis(), "Date From", data.filters().from(), "Date To", data.filters().to(),
                    "Factory", data.filters().factory().isEmpty() ? "ALL" : data.filters().factory(),
                    "Shipping", data.filters().shippingDate().isEmpty() ? "ALL" : data.filters().shippingDate(),
                    "Order", data.filters().orderId().isEmpty() ? "ALL" : data.filters().orderId(),
                    "Status", data.filters().status().isEmpty() ? "ALL" : data.filters().status());
            append(sheet, "Shipping Date", "Shipping Status", "Factory", "Order", "PO", "Master PO",
                    "Style", "SKU", "Color", "Destination", "Channel", "DC Code", "Planned CTN",
                    "RFID PASS", "RFID FAILED", "Weight PASS", "Weight FAILED",
                    "Final PASS", "Final FAILED", "Pending", "Status");
            for (PoRow r : data.rows()) {
                append(sheet, r.shippingDate().isEmpty() ? "NOT SCHEDULED" : r.shippingDate(),
                        r.shippingStatus(), r.factoryCode(), r.orderName(),
                        r.poNumber(), r.masterPo(), r.style(), r.sku(), r.color(), r.destination(),
                        r.channel(), r.dcCode(), r.plannedCartons(),
                        r.rfidPass(), r.rfidFailed(), r.weightPass(), r.weightFailed(), r.finalPass(),
                        r.finalFailed(), r.pending(), r.status());
            }
            formatReportSheet(book, sheet, 2, 21);
            book.write(out);
            return out.toByteArray();
        }
    }

    private byte[] cartonDetailsWorkbook(Data data, PoRow po) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Carton Details");
            append(sheet, "LULULEMON - Carton Details for PO " + excelText(po.poNumber()));
            append(sheet, "Shipping Date", po.shippingDate().isEmpty() ? "NOT SCHEDULED" : po.shippingDate(), "Factory", po.factoryCode(),
                    "Order", po.orderName(), "PO", po.poNumber(), "Master PO", po.masterPo());
            append(sheet, "Shipping Date", "Factory", "Order", "PO", "Master PO", "Carton No",
                    "SSCC-18", "Planned Qty", "Scanned Qty", "RFID", "Weight", "Final",
                    "Expected kg", "Actual kg", "RFID Time", "Weighed At", "Weighed By", "Updated At");
            long exportedCartons = 0;
            for (Carton c : data.cartons().getOrDefault(po.poId(), List.of())) {
                // Do not export carton records linked to another Order/Factory.
                if (!Objects.equals(po.orderId(), c.getOrderId())
                        || !po.factoryCode().equals(code(c.getFactoryCode()))) continue;
                if (++exportedCartons > 100000)
                    throw new IllegalArgumentException("Export has over 100,000 carton rows. Narrow the scope.");
                CartonRow cr = cartonRow(c);
                append(sheet, po.shippingDate(), po.factoryCode(), po.orderName(), po.poNumber(),
                        po.masterPo(), cr.cartonNo(), cr.sscc18(), cr.plannedQty(), cr.scannedQty(),
                        cr.rfidStatus(), cr.weightStatus(), cr.finalStatus(), cr.expectedWeightKg(),
                        cr.actualWeightKg(), cr.rfidVerifiedAt(), cr.weighedAt(), cr.weighedBy(), cr.updatedAt());
            }
            formatReportSheet(book, sheet, 2, 18);
            book.write(out);
            return out.toByteArray();
        }
    }
}
