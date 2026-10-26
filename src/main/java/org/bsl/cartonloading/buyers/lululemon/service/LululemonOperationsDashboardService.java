package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.PoPickClaim;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.model.RfidScanSession;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;

import java.util.*;
import java.util.stream.Collectors;

/** Read-only operational dashboard. Distinct PO means (Order ID, PO Number), not an ALL_BP row. */
@Service
public class LululemonOperationsDashboardService {
    private static final String BUYER = AppConstants.Buyer.LULULEMON;
    private final MongoTemplate mongo;
    private final BuyerFactoryAccessService access;
    private final FactoryCatalogService factories;
    public LululemonOperationsDashboardService(MongoTemplate mongo, BuyerFactoryAccessService access, FactoryCatalogService factories) {
        this.mongo = mongo; this.access = access; this.factories = factories;
    }

    public record Totals(int orders, int pos, long plannedCartons, int createdCartons,
                         int pickedPos, int notPickedPos, int identifiedCartons,
                         int weightPass, int weightFailed, int weightPending,
                         int rfidPass, int rfidFailed, int rfidNotScanned, int rfidInProgress,
                         int finalPass) { }
    public record OrderRow(String orderId, String orderName, int pos, int pickedPos, int notPickedPos,
                           long plannedCartons, int weightPass, int weightFailed, int rfidPass,
                           int rfidFailed, int finalPass) { }
    public record FactoryRow(String factoryCode, int pickedPos, long plannedCartons,
                             int identifiedCartons, int weightPass, int weightFailed, int weightPending,
                             int rfidPass, int rfidFailed, int rfidNotScanned, int finalPass,
                             List<String> pickedPoNumbers) { }
    public record PoRow(String orderId, String orderName, String poNumber, String factoryCode,
                        String pickedBy, boolean picked, String pickStatus, int sourceRows,
                        long plannedCartons, int createdCartons, int identifiedCartons,
                        int weightPass, int weightFailed, int weightPending,
                        int rfidPass, int rfidFailed, int rfidNotScanned, int rfidInProgress, int finalPass) { }
    public record CartonRow(String orderId, String poNumber, String factoryCode,
                            Integer cartonNo, String sscc18, int plannedQty, String status,
                            String weightStatus, String rfidStatus, String finalCheckStatus,
                            boolean rfidAttempted) { }
    public record Overview(Totals totals, List<OrderRow> orders,
                           List<FactoryRow> factories, List<PoRow> pos, List<CartonRow> cartons) { }

    private static String key(String orderId, String poNumber) { return orderId + "\u001F" + poNumber; }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String factory(String value) { return clean(value).toUpperCase(Locale.ROOT); }
    private static boolean yes(String value, String expect) { return expect.equalsIgnoreCase(clean(value)); }
    private static int qty(Integer value) { return Math.max(0, value == null ? 0 : value); }
    private static boolean rfidAttempted(Carton carton, Set<String> attempts) {
        return attempts.contains(clean(carton.getId())) || "PASS".equals(carton.getRfidStatus())
                || "FAILED".equals(carton.getRfidStatus()) || carton.getRfidVerifiedAt() != null;
    }

    public Overview overview() {
        var user = access.currentUser();
        if (!user.isEnabled() || !user.canAccessBuyer(BUYER))
            throw new AccessDeniedException("LULULEMON buyer access denied");
        boolean unrestricted = access.isUnrestricted();
        List<PurchaseOrder> raw = mongo.find(Query.query(Criteria.where("buyerCode").is(BUYER)), PurchaseOrder.class);
        Map<String, List<PurchaseOrder>> grouped = new TreeMap<>();
        for (PurchaseOrder row : raw) {
            if (clean(row.getOrderId()).isEmpty() || clean(row.getPoNumber()).isEmpty()) continue;
            grouped.computeIfAbsent(key(row.getOrderId(), row.getPoNumber()), k -> new ArrayList<>()).add(row);
        }
        Map<String, PoPickClaim> claims = mongo.find(Query.query(Criteria.where("buyerCode").is(BUYER)), PoPickClaim.class)
                .stream().filter(c -> "ACTIVE".equalsIgnoreCase(clean(c.getState())))
                .collect(Collectors.toMap(c -> key(c.getOrderId(), c.getPoNumber()), c -> c, (a,b) -> a));
        Map<String, PackingOrder> orders = mongo.find(Query.query(Criteria.where("buyerCode").is(BUYER)), PackingOrder.class)
                .stream().collect(Collectors.toMap(PackingOrder::getId, row -> row, (a,b) -> a));
        Map<String, List<Carton>> cartonGroups = new HashMap<>();
        for (Carton carton : mongo.find(Query.query(Criteria.where("buyerCode").is(BUYER)), Carton.class)) {
            if (clean(carton.getOrderId()).isEmpty() || clean(carton.getPoNumber()).isEmpty()) continue;
            cartonGroups.computeIfAbsent(key(carton.getOrderId(), carton.getPoNumber()), k -> new ArrayList<>()).add(carton);
        }
        Query rfidQuery = Query.query(Criteria.where("buyerCode").is(BUYER));
        rfidQuery.fields().include("cartonId");
        Set<String> rfidAttemptedCartons = mongo.find(rfidQuery, RfidScanSession.class)
                .stream().map(RfidScanSession::getCartonId).filter(Objects::nonNull).collect(Collectors.toSet());

        List<PoRow> poRows = new ArrayList<>();
        List<CartonRow> cartonRows = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            List<PurchaseOrder> lines = entry.getValue();
            PurchaseOrder first = lines.get(0);
            PoPickClaim claim = claims.get(entry.getKey());
            String owner = claim == null ? "" : clean(claim.getPickedBy());
            if (owner.isBlank()) owner = lines.stream().map(PurchaseOrder::getPickedBy)
                    .filter(v -> !clean(v).isBlank()).findFirst().orElse("");
            String factory = claim == null ? "" : factory(claim.getFactoryCode());
            if (factory.isBlank()) factory = lines.stream().map(PurchaseOrder::getFactoryCode)
                    .filter(v -> !factory(v).isBlank()).map(LululemonOperationsDashboardService::factory)
                    .findFirst().orElse("");
            // Operations users only see their assigned factories. Unpicked/unassigned PO is visible to ADMIN/SALES.
            if (!unrestricted && (factory.isBlank() || !access.canAccessAssignedFactory(factory))) continue;
            boolean picked = claim != null || !owner.isBlank();
            long planned = lines.stream().mapToLong(row -> qty(row.getPlannedCartons())).sum();
            List<Carton> cartons = cartonGroups.getOrDefault(entry.getKey(), List.of());
            int identified=0, wp=0,wf=0,rp=0,rf=0,ri=0,finalPass=0,attempted=0;
            for (Carton carton : cartons) {
                if (!clean(carton.getSscc18()).isEmpty()) identified++;
                if (yes(carton.getWeightStatus(), "PASS")) wp++;
                if (yes(carton.getWeightStatus(), "FAILED") || yes(carton.getWeightStatus(), "HOLD")) wf++;
                if (yes(carton.getRfidStatus(), "PASS")) rp++;
                if (yes(carton.getRfidStatus(), "FAILED")) rf++;
                if (yes(carton.getRfidStatus(), "SCANNING")) ri++;
                if (yes(carton.getFinalCheckStatus(), "FINAL_PASS")) finalPass++;
                boolean scanned = rfidAttempted(carton, rfidAttemptedCartons);
                if (scanned) attempted++;
                cartonRows.add(new CartonRow(carton.getOrderId(), carton.getPoNumber(), factory,
                        carton.getCartonNo(), carton.getSscc18(), qty(carton.getPlannedQty()), carton.getStatus(),
                        carton.getWeightStatus(), carton.getRfidStatus(), carton.getFinalCheckStatus(), scanned));
            }
            // Lazy-generated cartons count toward planned-but-not-weighed / not-scanned.
            int plannedOrCreated = (int)Math.max(planned, cartons.size());
            int pendingWeight = Math.max(0, plannedOrCreated - wp - wf);
            int notScanned = Math.max(0, plannedOrCreated - attempted);
            String orderName = orders.containsKey(first.getOrderId()) ? orders.get(first.getOrderId()).getOrderName() : "(Order not found)";
            poRows.add(new PoRow(first.getOrderId(), orderName, first.getPoNumber(), factory,
                    owner, picked, picked ? "PICKED" : "NOT_PICKED", lines.size(), planned, cartons.size(),
                    identified, wp, wf, pendingWeight, rp, rf, notScanned, ri, finalPass));
        }

        Map<String, List<PoRow>> byOrder = poRows.stream().collect(Collectors.groupingBy(PoRow::orderId, TreeMap::new, Collectors.toList()));
        List<OrderRow> orderRows = new ArrayList<>();
        for (var entry : byOrder.entrySet()) {
            List<PoRow> rows = entry.getValue();
            orderRows.add(new OrderRow(entry.getKey(), rows.get(0).orderName(), rows.size(),
                    (int)rows.stream().filter(PoRow::picked).count(), (int)rows.stream().filter(p -> !p.picked()).count(),
                    rows.stream().mapToLong(PoRow::plannedCartons).sum(),
                    rows.stream().mapToInt(PoRow::weightPass).sum(), rows.stream().mapToInt(PoRow::weightFailed).sum(),
                    rows.stream().mapToInt(PoRow::rfidPass).sum(), rows.stream().mapToInt(PoRow::rfidFailed).sum(),
                    rows.stream().mapToInt(PoRow::finalPass).sum()));
        }
        List<FactoryRow> factoryRows = new ArrayList<>();
        for (String code : factories.list(AppConstants.Buyer.LULULEMON, false).stream().map(org.bsl.cartonloading.model.Factory::getCode).toList()) {
            if (!unrestricted && !access.canAccessAssignedFactory(code)) continue;
            List<PoRow> rows = poRows.stream().filter(p -> code.equals(p.factoryCode()) && p.picked()).toList();
            List<String> numbers = rows.stream().map(PoRow::poNumber).distinct().sorted().toList();
            factoryRows.add(new FactoryRow(code, rows.size(), rows.stream().mapToLong(PoRow::plannedCartons).sum(),
                    rows.stream().mapToInt(PoRow::identifiedCartons).sum(),
                    rows.stream().mapToInt(PoRow::weightPass).sum(), rows.stream().mapToInt(PoRow::weightFailed).sum(),
                    rows.stream().mapToInt(PoRow::weightPending).sum(), rows.stream().mapToInt(PoRow::rfidPass).sum(),
                    rows.stream().mapToInt(PoRow::rfidFailed).sum(), rows.stream().mapToInt(PoRow::rfidNotScanned).sum(),
                    rows.stream().mapToInt(PoRow::finalPass).sum(), numbers));
        }
        Totals totals = new Totals(orderRows.size(), poRows.size(), poRows.stream().mapToLong(PoRow::plannedCartons).sum(),
                poRows.stream().mapToInt(PoRow::createdCartons).sum(),
                (int)poRows.stream().filter(PoRow::picked).count(), (int)poRows.stream().filter(p -> !p.picked()).count(),
                poRows.stream().mapToInt(PoRow::identifiedCartons).sum(),
                poRows.stream().mapToInt(PoRow::weightPass).sum(), poRows.stream().mapToInt(PoRow::weightFailed).sum(),
                poRows.stream().mapToInt(PoRow::weightPending).sum(), poRows.stream().mapToInt(PoRow::rfidPass).sum(),
                poRows.stream().mapToInt(PoRow::rfidFailed).sum(), poRows.stream().mapToInt(PoRow::rfidNotScanned).sum(),
                poRows.stream().mapToInt(PoRow::rfidInProgress).sum(), poRows.stream().mapToInt(PoRow::finalPass).sum());
        return new Overview(totals, orderRows, factoryRows, poRows, cartonRows);
    }
}
