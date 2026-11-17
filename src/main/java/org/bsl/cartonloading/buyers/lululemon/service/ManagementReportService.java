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
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/** Read-only reporting over persisted LULULEMON Shipping Day membership. Never changes a PO/carton. */
@Service
public class ManagementReportService {
    private static final String BUYER = AppConstants.Buyer.LULULEMON;
    private static final String SUBMISSIONS = "po_shipping_day_submissions";
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private final MongoTemplate mongo;
    private final BuyerFactoryAccessService factoryAccess;

    public ManagementReportService(MongoTemplate mongo, BuyerFactoryAccessService factoryAccess) {
        this.mongo = mongo;
        this.factoryAccess = factoryAccess;
    }

    public record Filters(LocalDate from, LocalDate to, String factory, String shippingDate, String orderId, String status) {}
    public record Totals(long orders, long factories, long shippingDays, long poAssignments,
                         long uniquePos, long plannedCartons, long generatedCartons,
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
    public record ShippingTrendPoint(String shippingDate, long plannedCartons, long finalPass) {}
    public record FactoryPerformancePoint(String factoryCode, long plannedCartons, long finalPass) {}
    public record QcStatus(long finalPass, long finalFailed, long pending) {}
    public record DashboardSummary(List<ShippingTrendPoint> shippingTrend,
                                   List<FactoryPerformancePoint> factoryPerformance,
                                   QcStatus qcStatus, List<String> availableFactories) {}
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
        if (!requestFactory.isEmpty() && !canReportFactory(factoryAccess.currentUser(), requestFactory))
            throw new AccessDeniedException("Factory report access denied");
        return new Filters(filters.from(), filters.to(), requestFactory, clean(filters.shippingDate()),
                clean(filters.orderId()), code(filters.status()));
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

    private record Data(Filters filters, List<PoRow> rows, Options options,
                        Map<String, List<Carton>> cartons) { }

    private Data load(Filters original) {
        Filters f = checked(original);
        // Indexed date predicate reduces the scan; membership() rechecks each date to avoid
        // Mongo array-boundary false positives and to preserve legacy plannedShippingDate.
        Criteria date = new Criteria().orOperator(
                Criteria.where("shippingDates").gte(f.from()).lte(f.to()),
                Criteria.where("plannedShippingDate").gte(f.from()).lte(f.to()));
        Criteria criteria = new Criteria().andOperator(Criteria.where("buyerCode").is(BUYER), date);
        if (!f.factory().isEmpty()) criteria = new Criteria().andOperator(criteria, Criteria.where("factoryCode").is(f.factory()));
        // Never use generic Factory access/isUnrestricted here: those let ADMIN bypass
        // Option 2. Reports have their own explicit, switch-independent scope.
        var reportUser = factoryAccess.currentUser();
        Set<String> allowed = reportUser.canLululemonReportAllFactories() ? null :
                new HashSet<>(reportUser.getFactoryPermissions());
        if (allowed != null) {
            // Restrict the database query as well as all downstream results.
            criteria = new Criteria().andOperator(criteria, Criteria.where("factoryCode").in(allowed));
        }
        List<PurchaseOrder> purchaseOrders = mongo.find(Query.query(criteria), PurchaseOrder.class);
        purchaseOrders.removeIf(p -> clean(p.getOrderId()).isEmpty() || clean(p.getId()).isEmpty()
                || clean(p.getFactoryCode()).isEmpty()
                || (allowed != null && !allowed.contains(code(p.getFactoryCode())))
                || (!f.factory().isEmpty() && !f.factory().equals(code(p.getFactoryCode()))));

        Set<String> orderIds = purchaseOrders.stream().map(PurchaseOrder::getOrderId).collect(Collectors.toSet());
        Map<String, String> orderNames = orderIds.isEmpty() ? Map.of() : mongo.find(Query.query(
                new Criteria().andOperator(Criteria.where("buyerCode").is(BUYER), Criteria.where("_id").in(orderIds))),
                OrderDocument.class).stream().collect(Collectors.toMap(OrderDocument::getId,
                o -> clean(o.getOrderName()).isEmpty() ? o.getId() : o.getOrderName(), (a, b) -> a));
        // Do not expose orphan PO records without an authorized parent Order.
        purchaseOrders.removeIf(p -> !orderNames.containsKey(p.getOrderId()));

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
        Map<String, Document> submissions = mongo.find(Query.query(new Criteria().andOperator(
                Criteria.where("buyerCode").is(BUYER),
                Criteria.where("shippingDate").gte(f.from().toString()).lte(f.to().toString()))),
                Document.class, SUBMISSIONS).stream()
                .filter(d -> d.getString("_id") != null)
                .collect(Collectors.toMap(d -> d.getString("_id"), d -> d, (a, b) -> a));

        List<PoRow> all = new ArrayList<>();
        for (PurchaseOrder p : purchaseOrders) {
            String fac = code(p.getFactoryCode());
            List<Carton> poCartons = cartons.getOrDefault(p.getId(), List.of()).stream()
                    .filter(c -> Objects.equals(p.getOrderId(), c.getOrderId()) && fac.equals(code(c.getFactoryCode())))
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
            for (LocalDate day : membership(p)) {
                if (day.isBefore(f.from()) || day.isAfter(f.to())) continue;
                String shippingDate = day.toString();
                all.add(new PoRow(shippingDate, submission(p.getOrderId(), fac, day, submissions), fac,
                        p.getOrderId(), orderNames.get(p.getOrderId()), p.getId(), p.getPoNumber(), p.getMasterPo(),
                        p.getStyle(), p.getSku(), p.getColor(), p.getDestination(), p.getChannel(), p.getDcCode(),
                        planned, poCartons.size(), rp, rf, wp, wf, fp, ff, pending, state));
            }
        }
        Options options = new Options(all.stream().map(PoRow::factoryCode).distinct().sorted().toList(),
                all.stream().map(PoRow::shippingDate).distinct().sorted(Comparator.reverseOrder()).toList(),
                all.stream().collect(Collectors.toMap(PoRow::orderId, r -> new OrderOption(r.orderId(), r.orderName()),
                        (a, b) -> a)).values().stream().sorted(Comparator.comparing(OrderOption::orderName)).toList());
        all.removeIf(r -> (!f.shippingDate().isEmpty() && !f.shippingDate().equals(r.shippingDate()))
                || (!f.orderId().isEmpty() && !f.orderId().equals(r.orderId()))
                || (!f.status().isEmpty() && !"ALL".equals(f.status()) && !f.status().equals(r.status())));
        all.sort(Comparator.comparing(PoRow::shippingDate).reversed()
                .thenComparing(PoRow::factoryCode).thenComparing(PoRow::orderName).thenComparing(PoRow::poNumber,
                        Comparator.nullsFirst(String::compareTo)));
        return new Data(f, all, options, cartons);
    }

    private static long sum(List<PoRow> rows, java.util.function.ToLongFunction<PoRow> getter) {
        return rows.stream().mapToLong(getter).sum();
    }
    private static Totals totals(List<PoRow> rows) {
        return new Totals(rows.stream().map(PoRow::orderId).distinct().count(),
                rows.stream().map(PoRow::factoryCode).distinct().count(),
                rows.stream().map(r -> r.factoryCode() + "|" + r.shippingDate() + "|" + r.orderId()).distinct().count(),
                rows.size(), rows.stream().map(PoRow::poId).distinct().count(),
                sum(rows, PoRow::plannedCartons), sum(rows, PoRow::generatedCartons),
                sum(rows, PoRow::rfidPass), sum(rows, PoRow::rfidFailed),
                sum(rows, PoRow::weightPass), sum(rows, PoRow::weightFailed),
                sum(rows, PoRow::finalPass), sum(rows, PoRow::finalFailed), sum(rows, PoRow::pending));
    }

    /** Lightweight choices for all six Dashboard filters. Factory choices are always
     * within the user's report grant; Shipping and Order choices follow the selected Factory.
     * Unlike report(), this query does not load carton QC or submission history.
     */
    public Options dashboardOptions(Filters original) {
        Filters f = checked(original);
        var user = factoryAccess.currentUser();
        Set<String> allowed = user.canLululemonReportAllFactories() ? null :
                new HashSet<>(user.getFactoryPermissions());
        Criteria dateCriteria = new Criteria().orOperator(
                Criteria.where("shippingDates").gte(f.from()).lte(f.to()),
                Criteria.where("plannedShippingDate").gte(f.from()).lte(f.to()));
        Criteria criteria = new Criteria().andOperator(Criteria.where("buyerCode").is(BUYER), dateCriteria);
        if (allowed != null) criteria = new Criteria().andOperator(criteria, Criteria.where("factoryCode").in(allowed));
        Query poQuery = Query.query(criteria);
        poQuery.fields().include("_id").include("orderId").include("factoryCode")
                .include("shippingDates").include("plannedShippingDate");
        List<PurchaseOrder> all = mongo.find(poQuery, PurchaseOrder.class);
        all.removeIf(p -> clean(p.getId()).isEmpty() || clean(p.getOrderId()).isEmpty()
                || clean(p.getFactoryCode()).isEmpty()
                || (allowed != null && !allowed.contains(code(p.getFactoryCode())))
                || membership(p).stream().noneMatch(day -> !day.isBefore(f.from()) && !day.isAfter(f.to())));

        Set<String> orderIds = all.stream().map(PurchaseOrder::getOrderId).collect(Collectors.toSet());
        Map<String, String> orderNames = orderIds.isEmpty() ? Map.of() : mongo.find(Query.query(
                new Criteria().andOperator(Criteria.where("buyerCode").is(BUYER), Criteria.where("_id").in(orderIds))),
                OrderDocument.class).stream().collect(Collectors.toMap(OrderDocument::getId,
                o -> clean(o.getOrderName()).isEmpty() ? o.getId() : o.getOrderName(), (a, b) -> a));
        all.removeIf(p -> !orderNames.containsKey(p.getOrderId()));
        List<String> factories = all.stream().map(p -> code(p.getFactoryCode())).distinct().sorted().toList();
        List<PurchaseOrder> scoped = all.stream()
                .filter(p -> f.factory().isEmpty() || f.factory().equals(code(p.getFactoryCode()))).toList();
        List<String> shippingDays = scoped.stream().flatMap(p -> membership(p).stream())
                .filter(day -> !day.isBefore(f.from()) && !day.isAfter(f.to()))
                .map(LocalDate::toString).distinct().sorted(Comparator.reverseOrder()).toList();
        List<OrderOption> orders = scoped.stream().map(PurchaseOrder::getOrderId).distinct()
                .map(id -> new OrderOption(id, orderNames.get(id)))
                .sorted(Comparator.comparing(OrderOption::orderName)).toList();
        return new Options(factories, shippingDays, orders);
    }

    /** Read-only chart aggregates. Never returns PO/carton details to the dashboard. */
    public DashboardSummary dashboardSummary(Filters filters) {
        Data data = load(filters); // checks Option 1/2/No Access and Factory scope (also for ADMIN)
        List<PoRow> rows = data.rows();
        List<ShippingTrendPoint> trend = rows.stream()
                .collect(Collectors.groupingBy(PoRow::shippingDate, TreeMap::new, Collectors.toList()))
                .entrySet().stream().map(entry -> new ShippingTrendPoint(entry.getKey(),
                        sum(entry.getValue(), PoRow::plannedCartons),
                        sum(entry.getValue(), PoRow::finalPass))).toList();
        List<FactoryPerformancePoint> factories = rows.stream()
                .collect(Collectors.groupingBy(PoRow::factoryCode, TreeMap::new, Collectors.toList()))
                .entrySet().stream().map(entry -> new FactoryPerformancePoint(entry.getKey(),
                        sum(entry.getValue(), PoRow::plannedCartons),
                        sum(entry.getValue(), PoRow::finalPass))).toList();
        return new DashboardSummary(trend, factories,
                new QcStatus(sum(rows, PoRow::finalPass), sum(rows, PoRow::finalFailed),
                        sum(rows, PoRow::pending)), data.options().factories());
    }

    public Report report(Filters filters, int page, int size) {
        Data data = load(filters);
        int perPage = Math.min(100, Math.max(1, size));
        int pageNo = Math.max(0, page);
        List<PoRow> rows = data.rows();
        Map<String, List<PoRow>> byFactory = rows.stream().collect(Collectors.groupingBy(PoRow::factoryCode, TreeMap::new, Collectors.toList()));
        List<FactoryRow> factories = byFactory.entrySet().stream().map(e -> new FactoryRow(e.getKey(), e.getValue().size(),
                sum(e.getValue(), PoRow::plannedCartons), sum(e.getValue(), PoRow::finalPass),
                sum(e.getValue(), PoRow::finalFailed), sum(e.getValue(), PoRow::pending))).toList();
        Map<String, List<PoRow>> byDay = rows.stream().collect(Collectors.groupingBy(r -> r.shippingDate() + "|" + r.factoryCode() + "|" + r.orderId(), TreeMap::new, Collectors.toList()));
        List<ShippingRow> shippingDays = byDay.values().stream().map(list -> {
            PoRow first = list.get(0);
            return new ShippingRow(first.shippingDate(), first.factoryCode(), first.orderId(), first.orderName(),
                    first.shippingStatus(), list.size(),
                    sum(list, PoRow::plannedCartons), sum(list, PoRow::finalPass),
                    sum(list, PoRow::finalFailed), sum(list, PoRow::pending));
        }).sorted(Comparator.comparing(ShippingRow::shippingDate).reversed()).toList();
        long start = (long) pageNo * perPage;
        List<PoRow> slice = start >= rows.size() ? List.of() : rows.subList((int) start, (int) Math.min(rows.size(), start + perPage));
        return new Report(data.filters(), totals(rows), factories, shippingDays,
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
            append(sheet, "LULULEMON - Shipping & Order Report");
            append(sheet, "Date From", data.filters().from(), "Date To", data.filters().to(),
                    "Factory", data.filters().factory().isEmpty() ? "ALL" : data.filters().factory(),
                    "Shipping", data.filters().shippingDate().isEmpty() ? "ALL" : data.filters().shippingDate(),
                    "Order", data.filters().orderId().isEmpty() ? "ALL" : data.filters().orderId(),
                    "Status", data.filters().status().isEmpty() ? "ALL" : data.filters().status());
            append(sheet, "Shipping Date", "Shipping Status", "Factory", "Order", "PO", "Master PO",
                    "Style", "SKU", "Color", "Destination", "Channel", "DC Code", "Planned CTN",
                    "RFID PASS", "RFID FAILED", "Weight PASS", "Weight FAILED",
                    "Final PASS", "Final FAILED", "Pending", "Status");
            for (PoRow r : data.rows()) {
                append(sheet, r.shippingDate(), r.shippingStatus(), r.factoryCode(), r.orderName(),
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
            append(sheet, "Shipping Date", po.shippingDate(), "Factory", po.factoryCode(),
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
