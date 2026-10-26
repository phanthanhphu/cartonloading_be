package org.bsl.cartonloading.service;

import org.bsl.cartonloading.common.constants.AppMessages;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.buyers.es.dto.PackingOrderRequest;
import org.bsl.cartonloading.buyers.es.dto.PackingOrderResponse;
import org.bsl.cartonloading.buyers.es.model.CartonScanTransaction;
import org.bsl.cartonloading.buyers.es.model.PackingAllocationLine;
import org.bsl.cartonloading.buyers.es.model.PackingListLine;
import org.bsl.cartonloading.buyers.es.repository.CartonScanTransactionRepository;
import org.bsl.cartonloading.buyers.es.repository.PackingAllocationLineRepository;
import org.bsl.cartonloading.buyers.es.repository.PackingListLineRepository;
import org.bsl.cartonloading.buyers.es.service.PackingOrderService;
import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.CartonItem;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.model.RfidScanSession;
import org.bsl.cartonloading.buyers.lululemon.service.TrueVueDecoder;
import org.bsl.cartonloading.buyers.lululemon.service.CartonScanProgress;
import org.bsl.cartonloading.buyers.lululemon.repository.CartonItemRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.CartonRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.PurchaseOrderRepository;
import org.bsl.cartonloading.buyers.lululemon.service.WorkflowService;
import org.bsl.cartonloading.dto.management.CartonManagementRow;
import org.bsl.cartonloading.dto.management.ItemManagementRow;
import org.bsl.cartonloading.dto.management.PoManagementRow;
import org.bsl.cartonloading.dto.management.PoInspectionProgress;
import org.bsl.cartonloading.dto.management.ScheduledPoInspectionSummary;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.repository.BuyerRepository;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OrderManagementService {
    private final PackingOrderService orders;
    private final PackingListLineRepository packingLines;
    private final PackingAllocationLineRepository allocationLines;
    private final CartonScanTransactionRepository scanTransactions;
    private final PurchaseOrderRepository purchaseOrders;
    private final CartonRepository ssccCartons;
    private final CartonItemRepository cartonItems;
    private final WorkflowService ssccWorkflow;
    private final AuditLogService audit;
    private final BuyerRepository buyers;
    private final MongoTemplate mongo;

    public OrderManagementService(PackingOrderService orders, PackingListLineRepository packingLines,
                                  PackingAllocationLineRepository allocationLines, CartonScanTransactionRepository scanTransactions,
                                  PurchaseOrderRepository purchaseOrders, CartonRepository ssccCartons,
                                  CartonItemRepository cartonItems, WorkflowService ssccWorkflow,
                                  AuditLogService audit, BuyerRepository buyers, MongoTemplate mongo) {
        this.orders = orders;
        this.packingLines = packingLines;
        this.allocationLines = allocationLines;
        this.scanTransactions = scanTransactions;
        this.purchaseOrders = purchaseOrders;
        this.ssccCartons = ssccCartons;
        this.cartonItems = cartonItems;
        this.ssccWorkflow = ssccWorkflow;
        this.audit = audit;
        this.buyers = buyers;
        this.mongo = mongo;
    }

    public Page<PackingOrderResponse> listOrders(String buyer, java.time.LocalDate orderDate, String orderName,
                                                        String factory, String supplier, String createdBy, String status,
                                                        int page, int size) {
        return orders.list(requiredBuyer(buyer), null, orderDate, orderName, supplier, factory, createdBy, status, null, null, page, size);
    }

    public PackingOrderResponse getOrder(String buyer, String id) {
        return orders.getResponse(requiredBuyer(buyer), id);
    }

    public PackingOrderResponse createOrder(String buyer, PackingOrderRequest request) {
        String code = requiredBuyer(buyer);
        PackingOrderResponse saved = orders.create(code, request);
        audit.log(RequestActor.current(), AppConstants.AuditAction.CREATE, AppConstants.AuditResource.ORDER, saved.id(), AppMessages.orderCreatedAudit(saved.orderName(), code), null, null);
        return saved;
    }

    public PackingOrderResponse updateOrder(String buyer, String id, PackingOrderRequest request) {
        String code = requiredBuyer(buyer);
        // Legacy factory assignment is manual by Admin and must agree with every imported PO.
        if (AppConstants.Buyer.LULULEMON.equals(code)) {
            String requestedFactory = request.factoryCode() == null ? "" : request.factoryCode().trim().toUpperCase(java.util.Locale.ROOT);
            if (purchaseOrders.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(id, code).stream()
                    .anyMatch(po -> po.getFactoryCode() == null || !requestedFactory.equalsIgnoreCase(po.getFactoryCode())))
                throw new IllegalArgumentException("Cannot assign Order to a Factory that differs from its existing ALL_BP POs. Resolve mixed-Factory data first.");
        }
        // An Order containing a completed-weight PO must not change its master data.
        if (AppConstants.Buyer.LULULEMON.equals(code)) {
            for (PurchaseOrder po : purchaseOrders.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(id, code)) {
                List<Carton> cartons = ssccCartons.findByPoIdOrderByCartonNoAsc(po.getId());
                if (AppConstants.WeighingSessionStatus.COMPLETED.equals(po.getWeighingSessionStatus())
                        || po.getWeighingCompletedAt() != null
                        || (!cartons.isEmpty() && cartons.stream().allMatch(c -> AppConstants.WeightStatus.PASS.equals(c.getWeightStatus())))) {
                    throw new IllegalArgumentException("Order has a PO with completed Carton Weight; Order editing is locked.");
                }
            }
        }
        PackingOrderResponse saved = orders.update(code, id, request);
        audit.log(RequestActor.current(), AppConstants.AuditAction.UPDATE, AppConstants.AuditResource.ORDER, id, AppMessages.orderUpdatedAudit(saved.orderName(), code), null, null);
        return saved;
    }

    public void deleteOrder(String buyer, String id) {
        String code = requiredBuyer(buyer);
        if (AppConstants.Buyer.LULULEMON.equals(code) && !purchaseOrders.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(id, code).isEmpty()) {
            throw new IllegalArgumentException(AppMessages.ORDER_WITH_PO_DATA_DELETE_BLOCKED);
        }
        orders.delete(code, id);
        audit.log(RequestActor.current(), AppConstants.AuditAction.DELETE, AppConstants.AuditResource.ORDER, id, AppMessages.orderDeletedAudit(code), null, null);
    }

    public Page<PoManagementRow> listPos(String buyer, String orderId, String poNumber, String factory,
                                             String style, String sku, String destination, String status,
                                             java.time.LocalDate exFtyDate, Boolean shippingScheduled, String inspectionStatus,
                                             String search, String masterPo, String description, String color, String qty, int page, int size) {
        String code = requiredBuyer(buyer);
        orders.getEntity(code, orderId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));

        if (AppConstants.Buyer.LULULEMON.equals(code)) {
            String normalizedInspection = inspectionStatus == null ? "" : inspectionStatus.trim().toUpperCase(Locale.ROOT);
            if (!Set.of("", "COMPLETED", "INCOMPLETE", "IMPORTING", "NOT_STARTED").contains(normalizedInspection)) {
                throw new IllegalArgumentException("Invalid inspection status filter.");
            }
            Map<String, InspectionCounts> allInspectionCounts = normalizedInspection.isEmpty() || "NOT_STARTED".equals(normalizedInspection)
                    ? Map.of() : loadInspectionCounts(orderId, code);
            List<PurchaseOrder> filteredPos = purchaseOrders.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, code).stream()
                    .filter(po -> matches(po.getPoNumber(), poNumber))
                    .filter(po -> matches(po.getMasterPo(), masterPo))
                    .filter(po -> matches(po.getDescription(), description))
                    .filter(po -> matches(po.getColor(), color))
                    .filter(po -> clean(qty) == null || po.getTotalQty() != null
                            && matches(String.valueOf(po.getTotalQty()), qty.replace(",", "")))
                    .filter(po -> matchesAnyPoField(po, search))
                    .filter(po -> matches(po.getFactoryCode(), factory))
                    .filter(po -> matches(po.getStyleNumber(), style))
                    .filter(po -> matches(po.getSku(), sku))
                    .filter(po -> matches(po.getDestination(), destination))
                    .filter(po -> matches(po.getStatus(), status))
                    .filter(po -> exFtyDate == null || exFtyDate.equals(po.getExFtyDate()))
                    .filter(po -> shippingScheduled == null
                            || shippingScheduled.equals(po.getPlannedShippingDate() != null))
                    .filter(po -> normalizedInspection.isEmpty()
                            || "NOT_STARTED".equals(normalizedInspection) && AppConstants.PurchaseOrderStatus.NOT_STARTED.equals(po.getStatus())
                            || !"NOT_STARTED".equals(normalizedInspection) && normalizedInspection.equals(progressFromCounts(po,
                                allInspectionCounts.getOrDefault(po.getId(), InspectionCounts.EMPTY)).status()))
                    .sorted(Boolean.TRUE.equals(shippingScheduled)
                            ? Comparator.comparing(PurchaseOrder::getPlannedShippingDate,
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(PurchaseOrder::getPoNumber,
                                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                            : Comparator.comparing(PurchaseOrder::getPoNumber,
                                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .toList();
            // Scan totals must come from persisted Cartons, not from ALL_BP Excel.
            // Fetch only the displayed PO page to avoid loading every Carton of large Orders.
            Pageable pageable = PageRequest.of(safePage, safeSize);
            int start = (int) Math.min(pageable.getOffset(), filteredPos.size());
            List<PurchaseOrder> displayedPos = filteredPos.subList(start, Math.min(start + safeSize, filteredPos.size()));
            List<String> poIds = displayedPos.stream().map(PurchaseOrder::getId).toList();
            Map<String, List<Carton>> cartonsByPo = poIds.isEmpty() ? Map.of() : ssccCartons.findByPoIdIn(poIds).stream()
                    .filter(carton -> Objects.equals(orderId, carton.getOrderId()) && Objects.equals(code, carton.getBuyerCode()))
                    .collect(Collectors.groupingBy(Carton::getPoId));
            List<PoManagementRow> rows = displayedPos.stream()
                    .map(po -> purchaseOrderRow(po, cartonsByPo.getOrDefault(po.getId(), List.of())))
                    .toList();
            return new PageImpl<>(rows, pageable, filteredPos.size());
        }
        requireBarcodePackingWorkflow(code);

        Map<String, List<PackingListLine>> grouped = packingLines.findByOrderIdAndBuyerCode(orderId, code).stream()
                .filter(line -> line.getPoNumber() != null && !line.getPoNumber().isBlank())
                .collect(Collectors.groupingBy(PackingListLine::getPoNumber, LinkedHashMap::new, Collectors.toList()));
        allocationLines.findByOrderIdAndBuyerCode(orderId, code).stream()
                .filter(line -> line.getPoNumber() != null && !line.getPoNumber().isBlank())
                .forEach(line -> grouped.computeIfAbsent(line.getPoNumber(), ignored -> new ArrayList<>(List.of(asPackingLine(line)))));
        List<CartonScanTransaction> cartons = scanTransactions.findByOrderIdAndBuyerCode(orderId, code);
        List<PoManagementRow> rows = grouped.entrySet().stream()
                .filter(e -> matches(e.getKey(), poNumber))
                .map(e -> {
                    List<PackingListLine> lines = e.getValue();
                    PackingListLine first = lines.get(0);
                    List<CartonScanTransaction> pc = cartons.stream().filter(c -> Objects.equals(e.getKey(), c.getPoNumber())).toList();
                    long itemCount = pc.stream().mapToLong(c -> quantity(c).longValue()).sum();
                    String rowStatus = pc.isEmpty() ? AppConstants.ProgressStatus.PLANNED : AppConstants.ProgressStatus.IN_PROGRESS;
                    return new PoManagementRow(e.getKey(), null, orderId, code, e.getKey(), first.getStyleNumber(), first.getArticleNumber(), null,
                            rowStatus, pc.size(), itemCount);
                })
                .filter(r -> matches(r.factoryCode(), factory))
                .filter(r -> matches(r.styleNumber(), style))
                .filter(r -> matches(r.sku(), sku))
                .filter(r -> matches(r.destination(), destination))
                .filter(r -> matches(r.status(), status))
                .sorted(Comparator.comparing(PoManagementRow::poNumber, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        return page(rows, safePage, safeSize);
    }

    public PoManagementRow getPo(String buyer, String orderId, String poKey) {
        String code = requiredBuyer(buyer);
        orders.getEntity(code, orderId);
        if (AppConstants.Buyer.LULULEMON.equals(code)) {
            return purchaseOrders.findByIdAndOrderIdAndBuyerCode(poKey, orderId, code)
                    .map(po -> purchaseOrderRow(po, ssccCartons.findByPoIdOrderByCartonNoAsc(po.getId())))
                    .orElseThrow(() -> new IllegalArgumentException(AppMessages.PO_NOT_FOUND));
        }
        requireBarcodePackingWorkflow(code);
        return listPos(code, orderId, poKey, null, null, null, null, null, null, null, null, null, null, null, null, null, 0, 200).getContent().stream()
                .filter(row -> Objects.equals(poKey, row.key()) || Objects.equals(poKey, row.poNumber()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(AppMessages.PO_NOT_FOUND));
    }

    public Page<CartonManagementRow> listCartons(String buyer, String orderId, String poKey,
                                                     String cartonNo, String sscc, String status,
                                                     int page, int size) {
        String code = requiredBuyer(buyer);
        orders.getEntity(code, orderId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        if (AppConstants.Buyer.LULULEMON.equals(code)) {
            ssccWorkflow.ensureCartonsForPo(code, orderId, poKey);
            // Filter and paginate cartons first: only read RFID sessions for the visible page.
            // Crucially, the stored carton.rfidVerifiedQty represents *all* unique TrueVUE
            // EPCs, including wrong SKU/cross-carton; it is NOT the matching EPC count.
            List<Carton> filtered = ssccCartons.findByPoIdOrderByCartonNoAsc(poKey).stream()
                    .filter(c -> Objects.equals(orderId, c.getOrderId()) && Objects.equals(code, c.getBuyerCode()))
                    .filter(c -> matches(String.valueOf(c.getCartonNo()), cartonNo))
                    .filter(c -> matches(c.getSscc18(), sscc))
                    .filter(c -> matches(c.getStatus(), status))
                    .toList();
            Page<Carton> selected = page(filtered, safePage, safeSize);
            List<String> sessionIds = selected.getContent().stream()
                    .map(Carton::getRfidSessionId).filter(Objects::nonNull).distinct().toList();
            Map<String, RfidScanSession> sessionById = new HashMap<>();
            if (!sessionIds.isEmpty()) {
                Query query = Query.query(Criteria.where("_id").in(sessionIds)
                        .and("buyerCode").is(code).and("orderId").is(orderId).and("poId").is(poKey));
                mongo.find(query, RfidScanSession.class).forEach(s -> sessionById.put(s.getId(), s));
            }
            List<CartonManagementRow> visible = selected.getContent().stream()
                    .map(c -> ssccCartonRow(c, sessionById.get(c.getRfidSessionId())))
                    .toList();
            return new PageImpl<>(visible, selected.getPageable(), selected.getTotalElements());
        }
        requireBarcodePackingWorkflow(code);
        List<CartonManagementRow> rows = scanTransactions.findByOrderIdAndBuyerCodeOrderByOrderCartonSequenceAsc(orderId, code).stream()
                .filter(c -> Objects.equals(poKey, c.getPoNumber()))
                .map(c -> new CartonManagementRow(c.getId(), orderId, code, poKey,
                        c.getCartonNumber() != null ? c.getCartonNumber() : c.getOrderCartonSequence(),
                        c.getLifecycleStatus(), c.getFactoryBarcode(), intValue(quantity(c)), null, c.getWeightKg(), c.getWeightStatus()))
                .filter(r -> matches(String.valueOf(r.cartonNo()), cartonNo))
                .filter(r -> matches(r.cartonIdentity(), sscc))
                .filter(r -> matches(r.status(), status))
                .toList();
        return page(rows, safePage, safeSize);
    }

    public CartonManagementRow getCarton(String buyer, String orderId, String cartonId) {
        String code = requiredBuyer(buyer);
        orders.getEntity(code, orderId);
        if (AppConstants.Buyer.LULULEMON.equals(code)) {
            Carton carton = ssccCartons.findByIdAndOrderIdAndBuyerCode(cartonId, orderId, code)
                    .orElseThrow(() -> new IllegalArgumentException(AppMessages.CARTON_NOT_FOUND));
            RfidScanSession session = carton.getRfidSessionId() == null ? null
                    : mongo.findById(carton.getRfidSessionId(), RfidScanSession.class);
            return ssccCartonRow(carton, session);
        }
        requireBarcodePackingWorkflow(code);
        CartonScanTransaction carton = scanTransactions.findByIdAndOrderIdAndBuyerCode(cartonId, orderId, code)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.CARTON_NOT_FOUND));
        return new CartonManagementRow(carton.getId(), orderId, code, carton.getPoNumber(),
                carton.getCartonNumber() != null ? carton.getCartonNumber() : carton.getOrderCartonSequence(),
                carton.getLifecycleStatus(), carton.getFactoryBarcode(), intValue(quantity(carton)), null,
                carton.getWeightKg(), carton.getWeightStatus());
    }

    public Page<ItemManagementRow> listItems(String buyer, String orderId, String cartonId,
                                                 String itemNo, String sku, String style, String color,
                                                 String sizeValue, String status, String scannedBy,
                                                 int page, int size) {
        String code = requiredBuyer(buyer);
        orders.getEntity(code, orderId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        if (AppConstants.Buyer.LULULEMON.equals(code)) {
            ssccWorkflow.ensureItemsForCarton(code, orderId, cartonId);
            // CartonItem stores physical scan state, not the imported product attributes.
            // Join the carton to its exact PO once (not once per item, and not by PO number)
            // so pending item slots can display Style/Color/Size before any SKU scan.
            Carton carton = ssccCartons.findByIdAndOrderIdAndBuyerCode(cartonId, orderId, code)
                    .orElseThrow(() -> new IllegalArgumentException(AppMessages.CARTON_NOT_FOUND));
            PurchaseOrder itemPo = purchaseOrders.findByIdAndOrderIdAndBuyerCode(carton.getPoId(), orderId, code)
                    .orElse(null);
            List<ItemManagementRow> rows = cartonItems.findByCartonIdOrderByItemNoAsc(cartonId).stream()
                    .filter(i -> Objects.equals(orderId, i.getOrderId()) && Objects.equals(code, i.getBuyerCode()))
                    .map(i -> cartonItemRow(i, itemPo, carton))
                    .filter(r -> matches(String.valueOf(r.itemNo()), itemNo))
                    .filter(r -> matches(r.sku(), sku))
                    .filter(r -> matches(r.style(), style))
                    .filter(r -> matches(r.color(), color))
                    .filter(r -> matches(r.size(), sizeValue))
                    .filter(r -> matches(r.status(), status))
                    .filter(r -> matches(r.scannedBy(), scannedBy))
                    .toList();
            return page(rows, safePage, safeSize);
        }
        requireBarcodePackingWorkflow(code);
        CartonScanTransaction c = scanTransactions.findByIdAndOrderIdAndBuyerCode(cartonId, orderId, code)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.CARTON_NOT_FOUND));
        List<ItemManagementRow> rows = List.of(new ItemManagementRow(c.getId() + "-product", cartonId, 1, c.getArticleNumber(), c.getStyle(), c.getColor(), c.getSize(),
                quantity(c), c.getLifecycleStatus(), c.getScannedBy(), c.getScannedAt())).stream()
                .filter(r -> matches(String.valueOf(r.itemNo()), itemNo))
                .filter(r -> matches(r.sku(), sku))
                .filter(r -> matches(r.style(), style))
                .filter(r -> matches(r.color(), color))
                .filter(r -> matches(r.size(), sizeValue))
                .filter(r -> matches(r.status(), status))
                .filter(r -> matches(r.scannedBy(), scannedBy))
                .toList();
        return page(rows, safePage, safeSize);
    }

    private record InspectionCounts(int generated, int rfidPass, int weightPass, int rfidFail, int weightFail) {
        private static final InspectionCounts EMPTY = new InspectionCounts(0, 0, 0, 0, 0);
    }

    private static int count(Document row, String key) {
        Object raw = row.get(key);
        return raw instanceof Number number ? number.intValue() : 0;
    }

    /** Mongo-side grouped totals, so filtering 900+ PO never loads every Carton document into Java. */
    private Map<String, InspectionCounts> loadInspectionCounts(String orderId, String buyerCode) {
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("orderId").is(orderId).and("buyerCode").is(buyerCode)),
                Aggregation.group("poId")
                        .count().as("generated")
                        .sum(ConditionalOperators.when(Criteria.where("rfidStatus").is("PASS")).then(1).otherwise(0)).as("rfidPass")
                        .sum(ConditionalOperators.when(Criteria.where("weightStatus").is(AppConstants.WeightStatus.PASS)).then(1).otherwise(0)).as("weightPass")
                        .sum(ConditionalOperators.when(Criteria.where("rfidStatus").is("FAILED")).then(1).otherwise(0)).as("rfidFail")
                        .sum(ConditionalOperators.when(Criteria.where("weightStatus").in(AppConstants.WeightStatus.FAILED, "HOLD")).then(1).otherwise(0)).as("weightFail")
        );
        Map<String, InspectionCounts> counts = new HashMap<>();
        for (Document row : mongo.aggregate(agg, Carton.class, Document.class).getMappedResults()) {
            String poId = row.getString("_id");
            if (poId == null) continue;
            counts.put(poId, new InspectionCounts(count(row, "generated"), count(row, "rfidPass"),
                    count(row, "weightPass"), count(row, "rfidFail"), count(row, "weightFail")));
        }
        return counts;
    }

    private static PoInspectionProgress progressFromCounts(PurchaseOrder po, InspectionCounts counts) {
        return PoInspectionProgress.fromCounts(po, counts.generated(), counts.rfidPass(), counts.weightPass(),
                counts.rfidFail(), counts.weightFail());
    }

    /** All scheduled PO, not just the current page or the current text/status filters. */
    public ScheduledPoInspectionSummary scheduledPoInspectionSummary(String buyer, String orderId) {
        String code = requiredBuyer(buyer);
        if (!AppConstants.Buyer.LULULEMON.equals(code)) {
            throw new IllegalArgumentException("Inspection summary is available for LULULEMON only.");
        }
        orders.getEntity(code, orderId);
        List<PurchaseOrder> scheduled = purchaseOrders.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, code)
                .stream().filter(po -> po.getPlannedShippingDate() != null).toList();
        Map<String, InspectionCounts> counts = loadInspectionCounts(orderId, code);
        long completed = 0, pendingRfid = 0, pendingWeight = 0, pendingBoth = 0, rfidFailed = 0, weightFailed = 0;
        for (PurchaseOrder po : scheduled) {
            PoInspectionProgress progress = progressFromCounts(po, counts.getOrDefault(po.getId(), InspectionCounts.EMPTY));
            if (progress.rfidComplete() && progress.weightComplete()) completed++;
            if (!progress.rfidComplete()) pendingRfid++;
            if (!progress.weightComplete()) pendingWeight++;
            if (!progress.rfidComplete() && !progress.weightComplete()) pendingBoth++;
            if (progress.rfidFailedCartons() > 0) rfidFailed++;
            if (progress.weightFailedCartons() > 0) weightFailed++;
        }
        return new ScheduledPoInspectionSummary(scheduled.size(), completed, scheduled.size() - completed,
                pendingRfid, pendingWeight, pendingBoth, rfidFailed, weightFailed);
    }

    private PoManagementRow purchaseOrderRow(PurchaseOrder po, List<Carton> cartons) {
        long plannedCartons = Optional.ofNullable(po.getCartonCount()).orElse(0);
        long plannedItems = Optional.ofNullable(po.getTotalQty()).orElse(0);
        CartonScanProgress progress = CartonScanProgress.from(po, cartons);
        return new PoManagementRow(
                po.getId(), po.getId(), po.getOrderId(), po.getBuyerCode(), po.getPoNumber(), po.getStyleNumber(), po.getSku(), po.getFactoryCode(), po.getStatus(),
                plannedCartons, plannedItems,
                po.getMasterPo(), po.getDcCode(), po.getDestination(), po.getChannel(), po.getPackingPlan(), po.getSalesOrderPts(), po.getDescription(), po.getColor(), po.getSize(),
                po.getTotalQty(), po.getQtyPerCarton(), po.getCartonCount(), po.getRemainderQty(), po.getShipMode(), po.getSeason(), po.getFwd(), po.getCartonBoxSize(),
                po.getNetWeightKg(), po.getGrossWeightKg(), po.getExFtyDate(), po.getPlannedShippingDate(), po.getAllBpHeaders(), po.getAllBpRows(),
                progress.scannedQty(), progress.scannedCartons(), progress.scannedFullCartons(),
                progress.scannedRemainderQty(), progress.scannedRemainderCartons(),
                progress.identifiedQty(), progress.identifiedCartons(), progress.identifiedFullCartons(),
                progress.identifiedRemainderQty(), progress.identifiedRemainderCartons(),
                progress.completedQty(), progress.completedCartons(), progress.completedFullCartons(),
                progress.completedRemainderQty(), progress.completedRemainderCartons(),
                AppConstants.WeighingSessionStatus.COMPLETED.equals(po.getWeighingSessionStatus())
                        || po.getWeighingCompletedAt() != null
                        || (!cartons.isEmpty() && cartons.stream().allMatch(c -> AppConstants.WeightStatus.PASS.equals(c.getWeightStatus()))),
                po.getPickedBy() != null && !po.getPickedBy().isBlank(),
                po.getSku() != null && !po.getSku().isBlank() || cartons.stream().anyMatch(c ->
                        c.getSscc18() != null && !c.getSscc18().isBlank()
                                || c.getStartedAt() != null || c.getWeighedAt() != null
                                || c.getWeightStatus() != null && !c.getWeightStatus().isBlank()),
                PoInspectionProgress.fromCartons(po, cartons)
        );
    }

    private CartonManagementRow ssccCartonRow(Carton c, RfidScanSession session) {
        // Check relationship because a session ID is never sufficient authorization
        // by itself. Missing legacy sessions remain null rather than fabricating
        // a matching EPC quantity from the old misleading rfidVerifiedQty.
        boolean valid = session != null
                && Objects.equals(session.getId(), c.getRfidSessionId())
                && Objects.equals(session.getCartonId(), c.getId())
                && Objects.equals(session.getOrderId(), c.getOrderId())
                && Objects.equals(session.getPoId(), c.getPoId())
                && Objects.equals(session.getBuyerCode(), c.getBuyerCode());
        Integer matched = null;
        Integer wrongSku = null;
        if (valid) {
            Set<String> cross = session.getCrossCartonEpcs() == null ? Set.of()
                    : new HashSet<>(session.getCrossCartonEpcs());
            Set<String> explicitWrong = session.getWrongSkuEpcs() == null ? Set.of()
                    : new HashSet<>(session.getWrongSkuEpcs());
            String expectedSku = TrueVueDecoder.skuKey(session.getExpectedSku());
            int matchedCount = 0;
            int wrongCount = 0;
            // Identical priority to CartonRfidPanel.getUniqueEpcRows():
            // cross-carton > wrong SKU > MATCH. Decode even older sessions so
            // mismatched EPCs are not counted as matches when flags are absent.
            for (String epc : session.getEpcs() == null ? List.<String>of() :
                    session.getEpcs().stream().filter(Objects::nonNull).distinct().toList()) {
                try {
                    String decodedSku = TrueVueDecoder.skuKey(TrueVueDecoder.decode(epc).productCode());
                    if (cross.contains(epc)) continue;
                    if (explicitWrong.contains(epc) || !Objects.equals(decodedSku, expectedSku)) wrongCount++;
                    else matchedCount++;
                } catch (IllegalArgumentException ignored) {
                    // Legacy malformed EPC is never considered a MATCH.
                }
            }
            matched = matchedCount;
            wrongSku = wrongCount;
        }
        return new CartonManagementRow(c.getId(), c.getOrderId(), c.getBuyerCode(), c.getPoId(), c.getCartonNo(), c.getStatus(), c.getSscc18(),
                c.getPlannedQty(), c.getScannedQty(), c.getActualWeightKg(), c.getWeightStatus(),
                c.getRfidStatus(), c.getRfidVerifiedQty(), c.getExpectedWeightKg(), c.getWeightToleranceKg(),
                matched, wrongSku, c.getLabelConfirmedSku());
    }

    /**
     * LULULEMON confirms a single carton-label SKU for all planned Item slots.
     * That value is saved in expectedSku, NOT scannedSku: scannedSku is reserved
     * for actual individual barcode scans. Expose the assigned SKU in the
     * management view without changing scan state, RFID status or audit data.
     */
    static ItemManagementRow cartonItemRow(CartonItem i, PurchaseOrder po, Carton carton) {
        boolean barcodeScanned = hasText(i.getScannedSku());
        boolean labelAssigned = !barcodeScanned && hasText(i.getExpectedSku())
                && carton != null && carton.getLabelConfirmedAt() != null
                && Objects.equals(i.getExpectedSku(), carton.getLabelConfirmedSku());
        String visibleSku = barcodeScanned ? i.getScannedSku()
                : (hasText(i.getExpectedSku()) ? i.getExpectedSku() : null);
        String operator = barcodeScanned || hasText(i.getScannedBy())
                ? i.getScannedBy()
                : (labelAssigned ? carton.getLabelConfirmedBy() : null);
        java.time.LocalDateTime recordedAt = i.getScannedAt() != null ? i.getScannedAt()
                : (labelAssigned ? carton.getLabelConfirmedAt() : null);
        return new ItemManagementRow(i.getId(), i.getCartonId(), i.getItemNo(), visibleSku,
                po == null ? null : po.getStyleNumber(),
                po == null ? null : po.getColor(),
                po == null ? null : po.getSize(),
                BigDecimal.ONE, i.getStatus(), operator, recordedAt);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private PackingListLine asPackingLine(PackingAllocationLine a) {
        PackingListLine p = new PackingListLine();
        p.setPoNumber(a.getPoNumber());
        p.setStyleNumber(a.getStyleNumber());
        p.setArticleNumber(a.getArticleNumber());
        p.setColor(a.getColor());
        p.setSize(a.getSize());
        return p;
    }

    private BigDecimal quantity(CartonScanTransaction c) {
        return c.getAssignedQuantity() != null ? c.getAssignedQuantity() :
                (c.getCartonPcs() != null ? c.getCartonPcs() :
                        (c.getQtyPerCarton() != null ? c.getQtyPerCarton() : BigDecimal.ZERO));
    }

    private Integer intValue(BigDecimal v) { return v == null ? null : v.intValue(); }

    private String requiredBuyer(String buyer) {
        String code = BuyerAccess.normalize(buyer);
        if (code.isBlank()) throw new IllegalArgumentException(AppMessages.invalidBuyer(buyer));
        if (buyers.findByBuyerKeyIgnoreCase(code).filter(b -> b.isActive()).isEmpty()) {
            throw new IllegalArgumentException(AppMessages.buyerInactiveOrMissing(code));
        }
        return code;
    }

    private void requireBarcodePackingWorkflow(String buyerCode) {
        if (!AppConstants.Buyer.ENGELBERT_STRAUSS.equals(buyerCode)) {
            throw new IllegalArgumentException(AppMessages.buyerWorkflowNotConfigured(buyerCode));
        }
    }

    private String clean(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private boolean matchesAnyPoField(PurchaseOrder po, String query) {
        String normalized = clean(query);
        if (normalized == null) return true;
        return contains(po.getPoNumber(), normalized)
                || contains(po.getMasterPo(), normalized)
                || contains(po.getStyleNumber(), normalized)
                || contains(po.getDescription(), normalized)
                || contains(po.getColor(), normalized)
                || contains(po.getSku(), normalized)
                || contains(po.getFactoryCode(), normalized)
                || contains(po.getDestination(), normalized)
                || (po.getTotalQty() != null && contains(String.valueOf(po.getTotalQty()), normalized));
    }

    private boolean matches(String value, String filter) {
        String q = clean(filter);
        return q == null || contains(value, q);
    }

    private boolean contains(String value, String q) {
        return value != null && q != null && value.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT));
    }

    private <T> Page<T> page(List<T> rows, int page, int size) {
        Pageable p = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200)));
        int from = Math.min((int) p.getOffset(), rows.size());
        int to = Math.min(from + p.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(from, to), p, rows.size());
    }
}
