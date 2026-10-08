package org.bsl.cartonloading.buyers.es.service;

import org.bsl.cartonloading.common.constants.AppMessages;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.service.RequestActor;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.buyers.es.enums.WeightSource;
import org.bsl.cartonloading.buyers.es.enums.WeightStatus;
import org.bsl.cartonloading.buyers.es.enums.ShipmentStatus;
import org.bsl.cartonloading.buyers.es.model.Shipment;
import org.bsl.cartonloading.buyers.es.support.ShipmentWorkflowPolicy;

import org.bsl.cartonloading.common.socket.AppSocketPublisher;
import org.bsl.cartonloading.buyers.es.dto.carton.*;
import org.bsl.cartonloading.buyers.es.dto.barcode.BarcodeAssignmentPageResponse;
import org.bsl.cartonloading.buyers.es.dto.barcode.PackingMasterAssignmentPageResponse;
import org.bsl.cartonloading.buyers.es.dto.barcode.PackingMasterAssignmentRowResponse;
import org.bsl.cartonloading.buyers.es.dto.barcode.FactoryBarcodeAssignRequest;
import org.bsl.cartonloading.buyers.es.dto.barcode.FactoryBarcodeScanRequest;
import org.bsl.cartonloading.buyers.es.enums.CartonScanStatus;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.buyers.es.model.CartonScanTransaction;
import org.bsl.cartonloading.buyers.es.model.FactoryBarcode;
import org.bsl.cartonloading.buyers.es.model.PackingAllocationLine;
import org.bsl.cartonloading.buyers.es.model.PackingListLine;
import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.bsl.cartonloading.buyers.es.model.ScaleStation;
import org.bsl.cartonloading.buyers.es.repository.CartonScanTransactionRepository;
import org.bsl.cartonloading.buyers.es.repository.PackingAllocationLineRepository;
import org.bsl.cartonloading.buyers.es.repository.PackingListLineRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class CartonLoadingService {

    private final PackingOrderService orderService;
    private final PackingAllocationLineRepository masterRepository;
    private final PackingListLineRepository packingRepository;
    private final CartonScanTransactionRepository transactionRepository;
    private final ScaleStationService stationService;
    private final FactoryBarcodeService factoryBarcodeService;
    private final MongoTemplate mongoTemplate;
    private final AppSocketPublisher socketPublisher;
    private final AuditLogService auditLogService;

    public CartonLoadingService(
            PackingOrderService orderService,
            PackingAllocationLineRepository masterRepository,
            PackingListLineRepository packingRepository,
            CartonScanTransactionRepository transactionRepository,
            ScaleStationService stationService,
            FactoryBarcodeService factoryBarcodeService,
            MongoTemplate mongoTemplate,
            AppSocketPublisher socketPublisher,
            AuditLogService auditLogService
    ) {
        this.orderService = orderService;
        this.masterRepository = masterRepository;
        this.packingRepository = packingRepository;
        this.transactionRepository = transactionRepository;
        this.stationService = stationService;
        this.factoryBarcodeService = factoryBarcodeService;
        this.mongoTemplate = mongoTemplate;
        this.socketPublisher = socketPublisher;
        this.auditLogService = auditLogService;
    }

    /**
     * Builds Carton Master rows from Master Data. Master Total ctns determines how many rows
     * are generated. One generated row represents one physical carton, so a unique Factory
     * Barcode can later be assigned 1:1 to that carton.
     *
     * The Factory Barcode pool remains independent. This method only prepares the carton side
     * of the mapping: Factory Barcode <-> Carton <-> Master Data.
     */
    public synchronized CartonPlanGenerationResult generateFromWsp(
            String buyerCode,
            String orderId,
            boolean replace
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        List<PackingAllocationLine> masterLines = masterRepository
                .findByOrderIdAndBuyerCode(order.getId(), buyer)
                .stream()
                .sorted(Comparator.comparing(PackingAllocationLine::getLineNo, Comparator.nullsLast(Integer::compareTo)))
                .toList();
        if (masterLines.isEmpty()) {
            return new CartonPlanGenerationResult(false, 0, 0, 0,
                    AppMessages.CARTON_MASTER_REQUIRES_ORDER_ITEMS);
        }

        List<PackingListLine> packingLines = packingRepository
                .findByOrderIdAndBuyerCode(order.getId(), buyer)
                .stream()
                .sorted(Comparator.comparing(PackingListLine::getLineNo, Comparator.nullsLast(Integer::compareTo)))
                .toList();
        List<CartonScanTransaction> existing = transactionRepository.findByOrderIdAndBuyerCode(order.getId(), buyer);
        if (!existing.isEmpty() && !replace) {
            return new CartonPlanGenerationResult(false, masterLines.size(), 0, 0,
                    AppMessages.CARTON_MASTER_ALREADY_EXISTS);
        }
        if (replace && existing.stream().anyMatch(this::hasOperationalData)) {
            throw new IllegalArgumentException(
                    AppMessages.CARTON_MASTER_REGEN_LOCKED
            );
        }
        if (replace) transactionRepository.deleteByOrderIdAndBuyerCode(order.getId(), buyer);

        /*
         * Master Data is the source of truth for the physical-carton count.
         * Total ctns determines how many CartonScanTransaction rows are generated.
         * Qty Per Ctn is only the planned piece quantity inside each physical carton.
         * Packing List rows are optional enrichment for carton number and expected gross weight;
         * they never increase or reduce the number of physical cartons generated for a Master row.
         */
        Map<String, Integer> itemGroupTotals = new LinkedHashMap<>();
        Map<String, List<PackingListLine>> packingByMaster = new LinkedHashMap<>();
        int skipped = 0;
        for (PackingAllocationLine master : masterLines) {
            BigDecimal qtyPerCarton = positive(master.getQtyPerCarton()) ? master.getQtyPerCarton() : null;
            int cartonCount = masterTotalCartons(master);
            if (!positive(qtyPerCarton) || cartonCount <= 0) {
                skipped++;
                continue;
            }
            itemGroupTotals.merge(itemGroupKey(master.getPoNumber(), master.getArticleNumber()), cartonCount, Integer::sum);
            packingByMaster.put(master.getId(), packingLinesForMaster(packingLines, master));
        }

        List<CartonScanTransaction> cartons = new ArrayList<>();
        Map<String, Integer> itemGroupCounters = new HashMap<>();
        int orderSequence = 0;
        LocalDateTime now = LocalDateTime.now();

        for (PackingAllocationLine master : masterLines) {
            BigDecimal qtyPerCarton = positive(master.getQtyPerCarton()) ? master.getQtyPerCarton() : null;
            int cartonCount = masterTotalCartons(master);
            if (!positive(qtyPerCarton) || cartonCount <= 0) continue;

            String groupKey = itemGroupKey(master.getPoNumber(), master.getArticleNumber());
            int itemTotal = itemGroupTotals.getOrDefault(groupKey, cartonCount);
            BigDecimal remainingPcs = positive(master.getTotalPcs()) ? master.getTotalPcs() : null;
            List<PackingListLine> relatedPackingLines = packingByMaster.getOrDefault(master.getId(), List.of());

            for (int cartonSequence = 1; cartonSequence <= cartonCount; cartonSequence++) {
                orderSequence++;
                int itemSequence = itemGroupCounters.merge(groupKey, 1, Integer::sum);
                BigDecimal cartonPcs = resolveCartonPcs(qtyPerCarton, remainingPcs, cartonSequence, cartonCount);
                if (remainingPcs != null && cartonPcs != null) {
                    remainingPcs = remainingPcs.subtract(cartonPcs).max(BigDecimal.ZERO);
                }

                PackingListLine packingLine = packingLineForMasterSequence(relatedPackingLines, cartonSequence);

                CartonScanTransaction carton = new CartonScanTransaction();
                carton.setBuyerCode(buyer);
                carton.setOrderId(order.getId());
                carton.setOrderName(order.getOrderName());
                carton.setMasterLineId(master.getId());
                carton.setPackingLineId(packingLine == null ? null : packingLine.getId());
                carton.setPackingLineNo(packingLine == null ? null : packingLine.getLineNo());
                carton.setSupplierName(master.getSupplierName());
                carton.setSupplierNumber(master.getSupplierNumber());
                carton.setPoNumber(master.getPoNumber());
                carton.setArticleNumber(master.getArticleNumber());
                carton.setStyleNumber(master.getStyleNumber());
                carton.setStyle(master.getStyle());
                carton.setColor(master.getColor());
                carton.setSize(master.getSize());
                carton.setQtyPerCarton(qtyPerCarton);
                carton.setCartonPcs(cartonPcs);
                carton.setCartonSequence(cartonSequence);
                carton.setPlannedCartons(cartonCount);
                carton.setOrderCartonSequence(orderSequence);
                carton.setCartonNumber(cartonNumberForMasterSequence(relatedPackingLines, cartonSequence, orderSequence));
                carton.setItemSequence(itemSequence);
                carton.setItemTotal(itemTotal);
                carton.setItemKey(buildItemKey(carton.getPoNumber(), carton.getArticleNumber(), itemSequence, itemTotal));
                carton.setCartonCode(carton.getItemKey());
                carton.setExpectedWeightKg(packingLine != null && positive(packingLine.getGrossWeightKg())
                        ? packingLine.getGrossWeightKg() : null);
                carton.setWeightStatus(WeightStatus.NOT_WEIGHED.name());
                carton.setStatus(CartonScanStatus.PLANNED);
                carton.setUpdatedAt(now);
                cartons.add(carton);
            }
        }

        if (cartons.isEmpty()) {
            return new CartonPlanGenerationResult(false, masterLines.size(), 0, skipped,
                    "No valid physical carton could be generated from Master Data. Check Total ctns and Qty Per Ctn.");
        }

        transactionRepository.saveAll(cartons);
        orderService.touchFromMaster(order);
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.PLAN_GENERATED, order.getId());
        return new CartonPlanGenerationResult(true, masterLines.size(), cartons.size(), skipped,
                "Carton Master generated from Master Total ctns. One row represents one physical carton.");
    }


    /**
     * True when Carton Master / physical-carton rows have already been generated for the Order.
     */
    public boolean hasGeneratedPlan(String buyerCode, String orderId) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        return transactionRepository.countByOrderIdAndBuyerCode(order.getId(), buyer) > 0;
    }

    /**
     * Quantity/source edits may automatically rebuild the generated physical-carton plan only
     * before barcode assignment / Weight Check starts. This prevents a source-row edit from
     * silently deleting or remapping an already identified physical carton.
     */
    public void assertGeneratedPlanEditable(String buyerCode, String orderId) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        List<CartonScanTransaction> existing = transactionRepository.findByOrderIdAndBuyerCode(order.getId(), buyer);
        if (existing.stream().anyMatch(this::hasOperationalData)) {
            throw new IllegalArgumentException(
                    AppMessages.CARTON_QUANTITY_CHANGE_LOCKED
            );
        }
    }

    /**
     * Keeps already-generated physical cartons synchronized with current Master Data and optional Packing List enrichment.
     * Master Total ctns always controls the physical-carton count. If Carton Master has not been generated yet this is intentionally a no-op.
     */
    public CartonPlanGenerationResult refreshGeneratedPlanIfPresent(String buyerCode, String orderId) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        if (transactionRepository.countByOrderIdAndBuyerCode(order.getId(), buyer) <= 0) {
            return new CartonPlanGenerationResult(false, 0, 0, 0,
                    AppMessages.CARTON_MASTER_NOT_GENERATED_NO_REFRESH);
        }
        assertGeneratedPlanEditable(buyer, order.getId());
        return generateFromWsp(buyer, order.getId(), true);
    }

    public Page<CartonScanTransaction> listCartons(
            String buyerCode,
            String orderId,
            String keyword,
            String status,
            int page,
            int pageSize
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        CartonScanStatus statusFilter = parseStatus(status);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(pageSize, 200));
        Pageable pageable = PageRequest.of(safePage, safeSize);

        Criteria criteria = Criteria.where("buyerCode").is(buyer).and("orderId").is(order.getId());
        if (statusFilter != null) {
            criteria = new Criteria().andOperator(criteria, Criteria.where("status").is(statusFilter));
        }
        Criteria keywordCriteria = cartonKeywordCriteria(keyword);
        if (keywordCriteria != null) {
            criteria = new Criteria().andOperator(criteria, keywordCriteria);
        }

        Query countQuery = Query.query(criteria);
        long total = mongoTemplate.count(countQuery, CartonScanTransaction.class);

        Query dataQuery = Query.query(criteria)
                .with(Sort.by(Sort.Direction.ASC, "orderCartonSequence"))
                .skip(pageable.getOffset())
                .limit(pageable.getPageSize());
        List<CartonScanTransaction> rows = mongoTemplate.find(dataQuery, CartonScanTransaction.class);
        return new PageImpl<>(rows, pageable, total);
    }

    /**
     * Returns the physical Carton Master rows linked to one WSP / Order Item row.
     */
    public List<CartonScanTransaction> listCartonsForItem(
            String buyerCode,
            String orderId,
            String masterLineId
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        String itemId = required(masterLineId, AppMessages.WSP_ITEM_REQUIRED);
        masterRepository.findById(itemId)
                .filter(item -> order.getId().equals(item.getOrderId()) && buyer.equals(item.getBuyerCode()))
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.WSP_ITEM_NOT_FOUND_SELECTED_ORDER));
        return transactionRepository
                .findByOrderIdAndBuyerCodeAndMasterLineIdOrderByCartonSequenceAsc(order.getId(), buyer, itemId);
    }

    /**
     * Returns the generated carton master/child rows used by the packing-line barcode assignment screen.
     * Assignment is deliberately separate from weighing: a Factory Barcode is first mapped to a carton,
     * then the same unique code is scanned later at the warehouse/weight station.
     */
    public BarcodeAssignmentPageResponse listCartonsForBarcodeAssignment(
            String buyerCode,
            String orderId,
            String keyword,
            String assignment,
            int page,
            int pageSize
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(10, Math.min(pageSize, 100));
        long offset = (long) safePage * safeSize;

        Criteria base = Criteria.where("buyerCode").is(buyer).and("orderId").is(order.getId());
        Criteria filtered = base;

        Criteria assignmentCriteria = assignmentCriteria(assignment);
        if (assignmentCriteria != null) {
            filtered = new Criteria().andOperator(filtered, assignmentCriteria);
        }

        Criteria keywordCriteria = cartonKeywordCriteria(keyword);
        if (keywordCriteria != null) {
            filtered = new Criteria().andOperator(filtered, keywordCriteria);
        }

        long totalElements = mongoTemplate.count(Query.query(filtered), CartonScanTransaction.class);
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / safeSize);

        // If the last row on the last page was just assigned/unassigned, keep the response on a valid page.
        int effectivePage = safePage;
        if (totalPages > 0 && effectivePage >= totalPages) {
            effectivePage = totalPages - 1;
            offset = (long) effectivePage * safeSize;
        }

        Query dataQuery = Query.query(filtered)
                .with(Sort.by(Sort.Direction.ASC, "orderCartonSequence"))
                .skip(offset)
                .limit(safeSize);
        List<CartonScanTransaction> content = mongoTemplate.find(dataQuery, CartonScanTransaction.class);

        long totalCartons = mongoTemplate.count(Query.query(base), CartonScanTransaction.class);
        long assignedCount = mongoTemplate.count(
                Query.query(new Criteria().andOperator(base, assignedBarcodeCriteria())),
                CartonScanTransaction.class
        );
        long unassignedCount = Math.max(0L, totalCartons - assignedCount);

        return new BarcodeAssignmentPageResponse(
                content,
                effectivePage,
                safeSize,
                totalElements,
                totalPages,
                totalCartons,
                assignedCount,
                unassignedCount
        );
    }

    /**
     * Stage 2 is intentionally Master-first. The screen shows one row per Master Data line and
     * opens the physical cartons only after Packing selects the matching PO / Article / Style.
     * Total ctns remains authoritative for the expected physical-carton count.
     */
    public PackingMasterAssignmentPageResponse listMasterRowsForBarcodeAssignment(
            String buyerCode,
            String orderId,
            String poNumber,
            String articleNumber,
            String styleNumber,
            String style,
            String assignmentStatus,
            int page,
            int pageSize
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(10, Math.min(pageSize, 100));

        List<PackingAllocationLine> masters = masterRepository
                .findByOrderIdAndBuyerCode(order.getId(), buyer)
                .stream()
                .sorted(Comparator.comparing(PackingAllocationLine::getLineNo, Comparator.nullsLast(Integer::compareTo)))
                .toList();

        List<CartonScanTransaction> cartons = transactionRepository.findByOrderIdAndBuyerCode(order.getId(), buyer);
        Map<String, List<CartonScanTransaction>> cartonsByMaster = cartons.stream()
                .filter(row -> !blank(row.getMasterLineId()))
                .collect(java.util.stream.Collectors.groupingBy(CartonScanTransaction::getMasterLineId));

        String poFilter = clean(poNumber);
        String articleFilter = clean(articleNumber);
        String styleNumberFilter = clean(styleNumber);
        String styleFilter = clean(style);
        String cleanStatus = clean(assignmentStatus);
        List<PackingMasterAssignmentRowResponse> allRows = new ArrayList<>();
        for (PackingAllocationLine master : masters) {
            if (!masterFieldMatches(master.getPoNumber(), poFilter)) continue;
            if (!masterFieldMatches(master.getArticleNumber(), articleFilter)) continue;
            if (!masterFieldMatches(master.getStyleNumber(), styleNumberFilter)) continue;
            if (!masterFieldMatches(master.getStyle(), styleFilter)) continue;

            List<CartonScanTransaction> children = cartonsByMaster.getOrDefault(master.getId(), List.of());
            int expected = masterTotalCartons(master);
            long generated = children.size();
            long assigned = children.stream().filter(row -> !blank(row.getFactoryBarcode())).count();
            long packed = children.stream().filter(row -> row.getAssignedQuantity() != null && row.getAssignedQuantity().signum() > 0).count();
            long remaining = Math.max(0L, expected - assigned);
            boolean countMatches = expected > 0 && generated == expected;
            String status = assignmentStatus(expected, generated, assigned);
            if (!masterAssignmentStatusMatches(status, cleanStatus)) continue;

            allRows.add(new PackingMasterAssignmentRowResponse(
                    master.getId(),
                    master.getLineNo(),
                    master.getPoNumber(),
                    master.getArticleNumber(),
                    master.getStyleNumber(),
                    master.getStyle(),
                    master.getColor(),
                    master.getSize(),
                    master.getQtyPerCarton(),
                    master.getTotalPcs(),
                    expected,
                    generated,
                    packed,
                    assigned,
                    remaining,
                    countMatches,
                    status
            ));
        }

        long totalElements = allRows.size();
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / safeSize);
        int effectivePage = safePage;
        if (totalPages > 0 && effectivePage >= totalPages) effectivePage = totalPages - 1;
        int from = Math.min(allRows.size(), effectivePage * safeSize);
        int to = Math.min(allRows.size(), from + safeSize);
        List<PackingMasterAssignmentRowResponse> content = allRows.subList(from, to);

        long totalExpected = allRows.stream().mapToLong(PackingMasterAssignmentRowResponse::totalCartons).sum();
        long totalGenerated = allRows.stream().mapToLong(PackingMasterAssignmentRowResponse::generatedCartons).sum();
        long totalAssigned = allRows.stream().mapToLong(PackingMasterAssignmentRowResponse::assignedCount).sum();

        return new PackingMasterAssignmentPageResponse(
                content, effectivePage, safeSize, totalElements, totalPages, totalElements,
                totalExpected, totalGenerated, totalAssigned
        );
    }

    private boolean masterFieldMatches(String value, String filter) {
        if (filter == null) return true;
        return value != null && value.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }

    private String assignmentStatus(int expected, long generated, long assigned) {
        if (expected <= 0 || generated != expected) return AppConstants.ProgressStatus.PLAN_MISMATCH;
        if (assigned <= 0) return AppConstants.ProgressStatus.NOT_STARTED;
        if (assigned >= expected) return AppConstants.ProgressStatus.COMPLETED;
        return AppConstants.ProgressStatus.IN_PROGRESS;
    }

    private boolean masterAssignmentStatusMatches(String status, String filter) {
        if (filter == null || AppConstants.Filter.ALL.equalsIgnoreCase(filter)) return true;
        if (AppConstants.Filter.UNASSIGNED.equalsIgnoreCase(filter)) return !AppConstants.ProgressStatus.COMPLETED.equalsIgnoreCase(status);
        if (AppConstants.Filter.ASSIGNED.equalsIgnoreCase(filter)) return AppConstants.ProgressStatus.COMPLETED.equalsIgnoreCase(status);
        return status.equalsIgnoreCase(filter);
    }

    public FactoryBarcode checkFactoryBarcodeForAssignment(String buyerCode, String orderId, String barcodeValue) {
        String buyer = requireBuyer(buyerCode);
        orderService.getEntity(buyer, orderId);
        return factoryBarcodeService.requireAvailable(barcodeValue);
    }

    public synchronized CartonScanTransaction assignFactoryBarcode(
            String buyerCode,
            String orderId,
            FactoryBarcodeAssignRequest request
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        if (request == null) throw new IllegalArgumentException(AppMessages.ASSIGNMENT_REQUEST_REQUIRED);
        String barcodeValue = required(request.factoryBarcode(), AppMessages.FACTORY_BARCODE_REQUIRED);
        String cartonId = required(request.cartonId(), AppMessages.CARTON_REQUIRED);

        CartonScanTransaction existingCarton = transactionRepository
                .findByIdAndOrderIdAndBuyerCode(cartonId, order.getId(), buyer)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.CARTON_MASTER_NOT_FOUND_SELECTED_ORDER));
        if (existingCarton.getStatus() != CartonScanStatus.PLANNED) {
            throw new IllegalArgumentException(AppMessages.factoryBarcodeStatusBlocksAssignment(existingCarton.getStatus()));
        }
        if (!blank(existingCarton.getFactoryBarcode())) {
            if (barcodeValue.equals(existingCarton.getFactoryBarcode())) return existingCarton;
            throw new IllegalArgumentException(AppMessages.cartonAlreadyHasFactoryBarcode(existingCarton.getCartonCode(), existingCarton.getFactoryBarcode()));
        }

        FactoryBarcode label = factoryBarcodeService.requireAvailable(barcodeValue);
        BigDecimal plannedQuantity = existingCarton.getCartonPcs() == null ? existingCarton.getQtyPerCarton() : existingCarton.getCartonPcs();
        BigDecimal actualQuantity = request.actualQuantity();
        ShipmentWorkflowPolicy.requireQuantity(actualQuantity, plannedQuantity);
        if (!blank(existingCarton.getShipmentId())) throw new IllegalArgumentException(AppMessages.CARTON_SHIPMENT_PLAN_LOCKED);
        LocalDateTime now = LocalDateTime.now();
        Query claimQuery = Query.query(Criteria.where("_id").is(cartonId)
                .and("buyerCode").is(buyer)
                .and("orderId").is(order.getId())
                .and("status").is(CartonScanStatus.PLANNED)
                .and("factoryBarcode").is(null).and("shipmentId").is(null));
        Update claimUpdate = new Update()
                .set("factoryBarcode", label.getBarcode())
                .set("assignedQuantity", actualQuantity)
                .set("productionLine", clean(request.productionLine()))
                .set("factoryBarcodeAssignedBy", RequestActor.current())
                .set("factoryBarcodeAssignedAt", now)
                .set("updatedAt", now);

        CartonScanTransaction claimed;
        try {
            claimed = mongoTemplate.findAndModify(
                    claimQuery,
                    claimUpdate,
                    FindAndModifyOptions.options().returnNew(true),
                    CartonScanTransaction.class
            );
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException(AppMessages.factoryBarcodeAssignedByAnotherOperator(label.getBarcode()));
        }
        if (claimed == null) {
            throw new IllegalArgumentException(AppMessages.CARTON_CHANGED_BEFORE_ASSIGNMENT);
        }

        try {
            factoryBarcodeService.assignToCarton(label, claimed);
        } catch (RuntimeException ex) {
            Query rollback = Query.query(Criteria.where("_id").is(claimed.getId()).and("factoryBarcode").is(label.getBarcode()));
            mongoTemplate.updateFirst(rollback, new Update()
                    .unset("factoryBarcode")
                    .unset("assignedQuantity")
                    .unset("productionLine")
                    .unset("factoryBarcodeAssignedBy")
                    .unset("factoryBarcodeAssignedAt")
                    .set("updatedAt", LocalDateTime.now()), CartonScanTransaction.class);
            throw ex;
        }

        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.FACTORY_BARCODE_ASSIGNED, claimed.getId());
        return claimed;
    }

    public synchronized CartonScanTransaction unassignFactoryBarcode(
            String buyerCode,
            String orderId,
            String cartonId
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        CartonScanTransaction carton = transactionRepository
                .findByIdAndOrderIdAndBuyerCode(required(cartonId, AppMessages.CARTON_REQUIRED), order.getId(), buyer)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.CARTON_MASTER_NOT_FOUND_SELECTED_ORDER));
        if (carton.getCompletedAt() != null || carton.getShippedAt() != null) {
            throw new IllegalArgumentException(AppMessages.COMPLETED_SHIPPED_CHANGE_LOCKED);
        }
        if (carton.getStatus() != CartonScanStatus.PLANNED || carton.getJobId() != null || carton.getWeightKg() != null) {
            throw new IllegalArgumentException(AppMessages.WEIGHT_CHECK_STARTED_BARCODE_CHANGE_BLOCKED);
        }
        if (!blank(carton.getShipmentId())) throw new IllegalArgumentException(AppMessages.CANCEL_SHIPMENT_BEFORE_UNASSIGN);
        String barcodeValue = clean(carton.getFactoryBarcode());
        if (barcodeValue == null) return carton;

        CartonScanTransaction saved = mongoTemplate.findAndModify(Query.query(Criteria.where("_id").is(carton.getId())
                        .and("factoryBarcode").is(barcodeValue).and("status").is(CartonScanStatus.PLANNED)
                        .and("shipmentId").is(null).and("jobId").is(null).and("weightKg").is(null).and("inspectedAt").is(null)
                        .and("completedAt").is(null).and("shippedAt").is(null)),
                new Update().unset("factoryBarcode").unset("factoryBarcodeAssignedBy").unset("factoryBarcodeAssignedAt")
                        .unset("assignedQuantity").unset("productionLine").set("updatedAt", LocalDateTime.now()),
                FindAndModifyOptions.options().returnNew(true), CartonScanTransaction.class);
        if (saved == null) throw new IllegalArgumentException(AppMessages.CARTON_CHANGED_UNASSIGN);
        try {
            factoryBarcodeService.releaseAssignment(barcodeValue, saved.getId());
        } catch (RuntimeException ex) {
            mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(saved.getId())
                            .and("factoryBarcode").is(null).and("status").is(CartonScanStatus.PLANNED)),
                    new Update().set("factoryBarcode", barcodeValue)
                            .set("factoryBarcodeAssignedBy", carton.getFactoryBarcodeAssignedBy())
                            .set("factoryBarcodeAssignedAt", carton.getFactoryBarcodeAssignedAt())
                            .set("assignedQuantity", carton.getAssignedQuantity()).set("productionLine", carton.getProductionLine()),
                    CartonScanTransaction.class);
            throw ex;
        }
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.FACTORY_BARCODE_UNASSIGNED, saved.getId());
        return saved;
    }

    /**
     * Admin correction flow for a single physical carton. Weight/scan data is reset, while the
     * Factory Barcode assignment remains intact. The Admin may then unassign/reassign that carton
     * and the carton must be weighed again. This never unlocks or rewinds other cartons in the Order.
     */
    public synchronized CartonScanTransaction resetCartonWeight(
            String buyerCode,
            String orderId,
            String cartonId,
            CartonWeightResetRequest request
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        String reason = required(request == null ? null : request.reason(), AppMessages.CORRECTION_REASON_REQUIRED);
        CartonScanTransaction carton = transactionRepository
                .findByIdAndOrderIdAndBuyerCode(required(cartonId, AppMessages.CARTON_REQUIRED), order.getId(), buyer)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.CARTON_MASTER_NOT_FOUND_SELECTED_ORDER));

        if (carton.getCompletedAt() != null || carton.getShippedAt() != null) {
            throw new IllegalArgumentException(AppMessages.COMPLETED_SHIPPED_RESET_LOCKED);
        }
        if (!hasWeightStarted(carton)) {
            throw new IllegalArgumentException(AppMessages.NO_WEIGHT_RESET_REQUIRED);
        }
        if (carton.getStatus() == CartonScanStatus.CANCELLED) {
            throw new IllegalArgumentException(AppMessages.CANCELLED_CARTON_CORRECTION_BLOCKED);
        }

        String actor = RequestActor.current();
        LocalDateTime now = LocalDateTime.now();
        String previousWeight = carton.getWeightKg() == null ? "N/A" : carton.getWeightKg().toPlainString() + " kg";
        String previousStatus = carton.getWeightStatus();

        carton.setJobId(null);
        carton.setStationCode(null);
        carton.setPalletCode(null);
        carton.setBarcode(null);
        carton.setScanId(null);
        carton.setWeightKg(null);
        carton.setWeightDifferenceKg(null);
        carton.setWeightStatus(WeightStatus.NOT_WEIGHED.name());
        carton.setStatus(CartonScanStatus.PLANNED);
        carton.setWarningMessage(null);
        carton.setPlcMessageId(null);
        carton.setManualReason(null);
        carton.setWeightSource(null);
        carton.setScannedBy(null);
        carton.setWeighedBy(null);
        carton.setScannedAt(null);
        carton.setWeighedAt(null);
        carton.setLastCorrectionReason(reason);
        carton.setLastCorrectedBy(actor);
        carton.setLastCorrectedAt(now);
        carton.setUpdatedAt(now);

        Update reset = new Update().set("weightStatus", WeightStatus.NOT_WEIGHED.name()).set("status", CartonScanStatus.PLANNED)
                .set("lastCorrectionReason", reason).set("lastCorrectedBy", actor).set("lastCorrectedAt", now).set("updatedAt", now);
        for (String field : List.of("jobId", "stationCode", "palletCode", "barcode", "scanId", "weightKg", "weightDifferenceKg",
                "warningMessage", "plcMessageId", "manualReason", "weightSource", "scannedBy", "weighedBy", "scannedAt", "weighedAt",
                "manualInspectionResult", "inspectionNote", "inspectedBy", "inspectedAt")) reset.unset(field);
        CartonScanTransaction saved = mongoTemplate.findAndModify(Query.query(Criteria.where("_id").is(carton.getId())
                .and("completedAt").is(null).and("shippedAt").is(null)), reset,
                FindAndModifyOptions.options().returnNew(true), CartonScanTransaction.class);
        if (saved == null) throw new IllegalArgumentException(AppMessages.CARTON_CONCURRENT_COMPLETION);
        auditLogService.log(
                actor,
                AppConstants.AuditAction.RESET_CARTON_WEIGHT,
                AppConstants.AuditResource.CARTON,
                saved.getId(),
                AppMessages.resetCartonWeightAudit(
                        order.getOrderName(),
                        firstText(saved.getCartonCode(), saved.getId()),
                        saved.getFactoryBarcode(),
                        previousWeight,
                        previousStatus,
                        reason
                ),
                null,
                null
        );
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.WEIGHT_RESET, saved.getId());
        return saved;
    }

    /**
     * Warehouse/weight-station scan. The Factory Barcode already points to one exact carton, so no
     * QA-code matching or 'first unfinished child' selection is performed here.
     */
    public synchronized CartonScanTransaction scanAssignedFactoryBarcode(
            String buyerCode,
            String orderId,
            FactoryBarcodeScanRequest request
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        if (request == null) throw new IllegalArgumentException(AppMessages.FACTORY_BARCODE_SCAN_REQUEST_REQUIRED);
        boolean manualMode = Boolean.TRUE.equals(request.manualMode()) || blank(request.stationCode());
        ScaleStation station = manualMode ? null : stationService.requireActive(request.stationCode());
        String stationCode = station == null ? null : station.getStationCode();
        String barcodeValue = required(request.factoryBarcode(), AppMessages.FACTORY_BARCODE_REQUIRED);
        String scanId = clean(request.scanId());

        if (scanId != null) {
            Optional<CartonScanTransaction> repeated = transactionRepository.findByScanId(scanId);
            if (repeated.isPresent()) {
                CartonScanTransaction existing = repeated.get();
                boolean sameStation = stationCode == null ? blank(existing.getStationCode()) : stationCode.equals(existing.getStationCode());
                if (!buyer.equals(existing.getBuyerCode()) || !order.getId().equals(existing.getOrderId()) || !sameStation
                        || !barcodeValue.equals(existing.getFactoryBarcode())) {
                    throw new IllegalArgumentException(AppMessages.SCAN_ID_ALREADY_USED);
                }
                return existing;
            }
        }

        if (stationCode != null) {
            Optional<CartonScanTransaction> openJob = transactionRepository
                    .findFirstByStationCodeAndStatusOrderByScannedAtDesc(stationCode, CartonScanStatus.WAITING_WEIGHT);
            if (openJob.isPresent()) {
                throw new IllegalArgumentException(AppMessages.stationJobWaiting(stationCode, openJob.get().getJobId()));
            }
        }

        FactoryBarcode label = factoryBarcodeService.requireAssigned(barcodeValue);
        if (!buyer.equals(label.getAssignedBuyerCode()) || !order.getId().equals(label.getAssignedOrderId())) {
            throw new IllegalArgumentException(AppMessages.factoryBarcodeBelongsToAnotherBuyerOrder(label.getBarcode()));
        }
        CartonScanTransaction carton = transactionRepository
                .findByIdAndOrderIdAndBuyerCode(label.getAssignedCartonId(), order.getId(), buyer)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.assignedCartonNotFoundForBarcode(label.getBarcode())));
        if (!label.getBarcode().equals(carton.getFactoryBarcode())) {
            throw new IllegalArgumentException(AppMessages.FACTORY_BARCODE_MAPPING_INCONSISTENT);
        }
        if (carton.getStatus() != CartonScanStatus.PLANNED) {
            throw new IllegalArgumentException(AppMessages.cartonAlreadyStatus(carton.getCartonCode(), carton.getStatus()));
        }

        requireCartonShipmentReady(carton);
        LocalDateTime now = LocalDateTime.now();
        long jobId = nextCounter("carton_loading_job");
        BigDecimal currentStandardWeight = carton.getExpectedWeightKg();
        if (!blank(carton.getPackingLineId())) {
            Optional<PackingListLine> currentPackingLine = packingRepository.findByIdAndOrderIdAndBuyerCode(
                    carton.getPackingLineId(), order.getId(), buyer);
            if (currentPackingLine.isPresent()) {
                BigDecimal latestGrossWeight = currentPackingLine.get().getGrossWeightKg();
                currentStandardWeight = positive(latestGrossWeight) ? latestGrossWeight : null;
            }
        }
        Query claimQuery = Query.query(Criteria.where("_id").is(carton.getId())
                .and("buyerCode").is(buyer)
                .and("orderId").is(order.getId())
                .and("factoryBarcode").is(label.getBarcode()).and("shipmentId").is(carton.getShipmentId())
                .and("status").is(CartonScanStatus.PLANNED));
        Update claimUpdate = new Update()
                .set("jobId", jobId)
                .set("palletCode", clean(request.palletCode()))
                .set("barcode", label.getBarcode())
                .set("status", CartonScanStatus.WAITING_WEIGHT)
                .set("weightToleranceKg", defaultTolerance(station))
                .set("scannedBy", RequestActor.current())
                .set("scannedAt", now)
                .set("updatedAt", now);
        if (currentStandardWeight == null) claimUpdate.unset("expectedWeightKg"); else claimUpdate.set("expectedWeightKg", currentStandardWeight);
        if (stationCode == null) claimUpdate.unset("stationCode"); else claimUpdate.set("stationCode", stationCode);
        if (scanId != null) claimUpdate.set("scanId", scanId);

        try {
            CartonScanTransaction claimed = mongoTemplate.findAndModify(
                    claimQuery,
                    claimUpdate,
                    FindAndModifyOptions.options().returnNew(true),
                    CartonScanTransaction.class
            );
            if (claimed == null) throw new IllegalArgumentException(AppMessages.CARTON_TAKEN_BY_OTHER_STATION);
            socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.WAITING_WEIGHT, claimed.getId());
            return claimed;
        } catch (DuplicateKeyException ex) {
            if (scanId != null) {
                Optional<CartonScanTransaction> repeated = transactionRepository.findByScanId(scanId);
                if (repeated.isPresent()) return repeated.get();
            }
            throw new IllegalArgumentException(AppMessages.FACTORY_BARCODE_SCAN_DUPLICATE_OR_BUSY);
        }
    }

    /**
     * Scan-first lookup: the physical labels can be identical, so the barcode identifies the WSP parent item.
     * The matching parent item is shown on the web page; scanning reserves the first unfinished child automatically.
     */
    public CartonItemLookupResponse lookupGeneratedItems(
            String buyerCode,
            String orderId,
            CartonItemLookupRequest request
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        String barcode = required(request.barcode(), AppMessages.BARCODE_REQUIRED);
        String normalized = normalizeBarcode(barcode);
        ParsedQr parsed = parseQr(barcode);

        List<CartonScanTransaction> matched = transactionRepository
                .findByOrderIdAndBuyerCodeOrderByOrderCartonSequenceAsc(order.getId(), buyer)
                .stream()
                .filter(row -> row.getMasterLineId() != null)
                .filter(row -> qrMatchesItem(parsed, normalized, row))
                .toList();

        Map<String, List<CartonScanTransaction>> grouped = new LinkedHashMap<>();
        for (CartonScanTransaction row : matched) {
            grouped.computeIfAbsent(row.getMasterLineId(), ignored -> new ArrayList<>()).add(row);
        }
        List<CartonMasterItemResponse> items = grouped.values().stream()
                .map(this::masterItemSummary)
                .toList();
        String message = items.isEmpty()
                ? AppMessages.QA_BARCODE_NO_MASTER_MATCH
                : items.size() == 1
                    ? AppMessages.ITEM_MATCHED_AUTO_CHILD
                    : AppMessages.QA_BARCODE_AMBIGUOUS_MASTER;
        return new CartonItemLookupResponse(!items.isEmpty(), barcode, normalized, message, items);
    }

    /**
     * Handles one completed Zebra USB HID scan.
     *
     * The QA code identifies a single WSP/Master Data row. Because every physical carton
     * can carry the same QA code, the backend atomically reserves the first remaining
     * PLANNED child carton and immediately creates the PLC waiting Job.
     */
    public synchronized CartonScanTransaction scanNextFromZebra(
            String buyerCode,
            String orderId,
            ZebraScanRequest request
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        boolean manualMode = Boolean.TRUE.equals(request.manualMode()) || blank(request.stationCode());
        ScaleStation station = manualMode ? null : stationService.requireActive(request.stationCode());
        String stationCode = station == null ? null : station.getStationCode();
        String barcode = required(request.barcode(), AppMessages.QA_CODE_REQUIRED);
        String selectedMasterLineId = clean(request.masterLineId());
        String scanId = clean(request.scanId());

        /* A browser retry with the same scanId returns the original Job instead of consuming another carton. */
        if (scanId != null) {
            Optional<CartonScanTransaction> repeated = transactionRepository.findByScanId(scanId);
            if (repeated.isPresent()) {
                CartonScanTransaction existing = repeated.get();
                boolean sameStation = stationCode == null
                        ? blank(existing.getStationCode())
                        : stationCode.equals(existing.getStationCode());
                if (!buyer.equals(existing.getBuyerCode())
                        || !order.getId().equals(existing.getOrderId())
                        || !sameStation) {
                    throw new IllegalArgumentException(AppMessages.SCAN_ID_ALREADY_USED);
                }
                return existing;
            }
        }

        if (stationCode != null) {
            Optional<CartonScanTransaction> openJob = transactionRepository
                    .findFirstByStationCodeAndStatusOrderByScannedAtDesc(stationCode, CartonScanStatus.WAITING_WEIGHT);
            if (openJob.isPresent()) {
                throw new IllegalArgumentException(AppMessages.stationJobWaiting(stationCode, openJob.get().getJobId()));
            }
        }

        ParsedQr parsed = parseQr(barcode);
        String normalized = normalizeBarcode(barcode);
        List<CartonScanTransaction> matchedRows = transactionRepository
                .findByOrderIdAndBuyerCodeOrderByOrderCartonSequenceAsc(order.getId(), buyer)
                .stream()
                .filter(row -> row.getMasterLineId() != null)
                .filter(row -> selectedMasterLineId == null || selectedMasterLineId.equals(row.getMasterLineId()))
                .filter(row -> qrMatchesItem(parsed, normalized, row))
                .toList();

        if (matchedRows.isEmpty()) {
            if (selectedMasterLineId != null) {
                throw new IllegalArgumentException(
                        AppMessages.QA_SELECTED_ITEM_MISMATCH
                );
            }
            throw new IllegalArgumentException(
                    AppMessages.QA_NO_MASTER_ITEM
            );
        }

        Map<String, List<CartonScanTransaction>> availableByMaster = new LinkedHashMap<>();
        for (CartonScanTransaction row : matchedRows) {
            if (row.getStatus() == CartonScanStatus.PLANNED) {
                availableByMaster.computeIfAbsent(row.getMasterLineId(), ignored -> new ArrayList<>()).add(row);
            }
        }
        if (availableByMaster.isEmpty()) {
            throw new IllegalArgumentException(AppMessages.QA_ALL_CARTONS_SCANNED);
        }
        if (availableByMaster.size() > 1) {
            String choices = availableByMaster.values().stream()
                    .map(rows -> rows.get(0))
                    .map(row -> "Size " + (blank(row.getSize()) ? "-" : row.getSize())
                            + " / Color " + (blank(row.getColor()) ? "-" : row.getColor()))
                    .distinct()
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("multiple Master Data rows");
            throw new IllegalArgumentException(
                    AppMessages.qaCodeAmbiguous(choices)
            );
        }

        String masterLineId = availableByMaster.keySet().iterator().next();
        LocalDateTime now = LocalDateTime.now();
        long jobId = nextCounter("carton_loading_job");

        Query claimQuery = Query.query(Criteria.where("buyerCode").is(buyer)
                        .and("orderId").is(order.getId())
                        .and("masterLineId").is(masterLineId)
                        .and("status").is(CartonScanStatus.PLANNED))
                .with(Sort.by(Sort.Direction.ASC, "cartonSequence", "orderCartonSequence"));
        Update claimUpdate = new Update()
                .set("jobId", jobId)
                .set("palletCode", clean(request.palletCode()))
                .set("barcode", barcode.trim())
                .set("status", CartonScanStatus.WAITING_WEIGHT)
                .set("weightToleranceKg", defaultTolerance(station))
                .set("scannedBy", RequestActor.current())
                .set("scannedAt", now)
                .set("updatedAt", now);
        if (stationCode == null) {
            claimUpdate.unset("stationCode");
        } else {
            claimUpdate.set("stationCode", stationCode);
        }
        if (scanId != null) claimUpdate.set("scanId", scanId);

        try {
            CartonScanTransaction claimed = mongoTemplate.findAndModify(
                    claimQuery,
                    claimUpdate,
                    FindAndModifyOptions.options().returnNew(true),
                    CartonScanTransaction.class
            );
            if (claimed == null) {
                throw new IllegalArgumentException(
                        AppMessages.NEXT_CARTON_TAKEN
                );
            }
            socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.WAITING_WEIGHT, claimed.getId());
            return claimed;
        } catch (DuplicateKeyException ex) {
            if (scanId != null) {
                Optional<CartonScanTransaction> repeated = transactionRepository.findByScanId(scanId);
                if (repeated.isPresent()) return repeated.get();
            }
            throw new IllegalArgumentException(AppMessages.QA_SCAN_DUPLICATE);
        }
    }

    public synchronized CartonScanTransaction completePlannedCartonManually(
            String buyerCode,
            String orderId,
            String cartonId,
            CartonManualCompleteRequest request
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        ScaleStation station = stationService.requireActive(request.stationCode());
        String stationCode = station.getStationCode();

        Optional<CartonScanTransaction> openJob = transactionRepository
                .findFirstByStationCodeAndStatusOrderByScannedAtDesc(stationCode, CartonScanStatus.WAITING_WEIGHT);
        if (openJob.isPresent()) {
            throw new IllegalArgumentException(AppMessages.stationJobWaitingForPlc(stationCode, openJob.get().getJobId()));
        }

        CartonScanTransaction carton = transactionRepository
                .findByIdAndOrderIdAndBuyerCode(cartonId, order.getId(), buyer)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.CHILD_ITEM_NOT_FOUND));
        if (carton.getStatus() != CartonScanStatus.PLANNED) {
            throw new IllegalArgumentException(AppMessages.itemAlreadyStatus(carton.getCartonCode(), carton.getStatus()));
        }

        String barcode = required(request.barcode(), AppMessages.BARCODE_REQUIRED);
        if (!qrMatchesItem(parseQr(barcode), normalizeBarcode(barcode), carton)) {
            throw new IllegalArgumentException(AppMessages.scannedQrMismatch(carton.getPoNumber(), carton.getArticleNumber()));
        }
        BigDecimal weight = requireWeight(request.weightKg(), station);
        String reason = required(request.reason(), AppMessages.MANUAL_INPUT_REASON_REQUIRED);
        LocalDateTime now = LocalDateTime.now();
        carton.setJobId(nextCounter("carton_loading_job"));
        carton.setStationCode(stationCode);
        carton.setPalletCode(clean(request.palletCode()));
        carton.setBarcode(barcode.trim());
        carton.setScannedBy(RequestActor.current());
        carton.setScannedAt(now);
        carton.setWeightToleranceKg(defaultTolerance(station));
        carton.setUpdatedAt(now);
        return completeWeight(
                carton, weight, "MANUAL-" + System.currentTimeMillis(),
                RequestActor.current(), reason, WeightSource.MANUAL.name()
        );
    }

    public synchronized CartonScanTransaction scanPlannedCarton(
            String buyerCode,
            String orderId,
            String cartonId,
            CartonPlanScanRequest request
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        ScaleStation station = stationService.requireActive(request.stationCode());
        String stationCode = station.getStationCode();

        Optional<CartonScanTransaction> openJob = transactionRepository
                .findFirstByStationCodeAndStatusOrderByScannedAtDesc(stationCode, CartonScanStatus.WAITING_WEIGHT);
        if (openJob.isPresent()) {
            throw new IllegalArgumentException(AppMessages.stationJobWaiting(stationCode, openJob.get().getJobId()));
        }

        CartonScanTransaction carton = transactionRepository
                .findByIdAndOrderIdAndBuyerCode(cartonId, order.getId(), buyer)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.CHILD_ITEM_NOT_FOUND));
        if (carton.getStatus() != CartonScanStatus.PLANNED) {
            throw new IllegalArgumentException(AppMessages.itemAlreadyStatus(carton.getCartonCode(), carton.getStatus()));
        }

        String barcode = required(request.barcode(), AppMessages.BARCODE_REQUIRED);
        if (!qrMatchesItem(parseQr(barcode), normalizeBarcode(barcode), carton)) {
            throw new IllegalArgumentException(AppMessages.scannedQrMismatch(carton.getPoNumber(), carton.getArticleNumber()));
        }

        LocalDateTime now = LocalDateTime.now();
        carton.setJobId(nextCounter("carton_loading_job"));
        carton.setStationCode(stationCode);
        carton.setPalletCode(clean(request.palletCode()));
        carton.setBarcode(barcode.trim());
        carton.setStatus(CartonScanStatus.WAITING_WEIGHT);
        carton.setWeightToleranceKg(defaultTolerance(station));
        carton.setScannedBy(RequestActor.current());
        carton.setScannedAt(now);
        carton.setUpdatedAt(now);
        try {
            CartonScanTransaction saved = transactionRepository.save(carton);
            socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.WAITING_WEIGHT, saved.getId());
            return saved;
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException(AppMessages.ANOTHER_ITEM_JOB_CREATED);
        }
    }

    /* Backward-compatible barcode lookup based on the generated Packing List. */
    public CartonLookupResponse lookup(String buyerCode, String orderId, CartonLookupRequest request) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        stationService.requireActive(request.stationCode());
        String barcode = required(request.barcode(), AppMessages.BARCODE_REQUIRED);
        String normalized = normalizeBarcode(barcode);

        List<PackingListLine> matches = packingRepository.findByOrderIdAndBuyerCode(order.getId(), buyer).stream()
                .filter(line -> barcodeMatches(normalized, line.getArticleNumber()))
                .sorted(Comparator.comparing(PackingListLine::getLineNo, Comparator.nullsLast(Integer::compareTo)))
                .toList();

        List<CartonCandidateResponse> candidates = matches.stream().map(this::candidate).toList();
        String message = candidates.isEmpty()
                ? AppMessages.BARCODE_NO_PACKING_LIST_MATCH
                : candidates.size() == 1
                    ? "Packing List row matched."
                    : AppMessages.BARCODE_AMBIGUOUS_PACKING_LIST;
        return new CartonLookupResponse(!candidates.isEmpty(), barcode, normalized, message, candidates, progress(buyer, orderId));
    }

    /* Backward-compatible transaction creation from a Packing List parent row. */
    public synchronized CartonScanTransaction start(
            String buyerCode,
            String orderId,
            CartonStartRequest request
    ) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, orderId);
        ScaleStation station = stationService.requireActive(request.stationCode());
        String stationCode = station.getStationCode();

        Optional<CartonScanTransaction> openJob = transactionRepository
                .findFirstByStationCodeAndStatusOrderByScannedAtDesc(stationCode, CartonScanStatus.WAITING_WEIGHT);
        if (openJob.isPresent()) {
            throw new IllegalArgumentException(AppMessages.stationJobWaiting(stationCode, openJob.get().getJobId()));
        }

        String barcode = required(request.barcode(), AppMessages.BARCODE_REQUIRED);
        PackingListLine line = packingRepository.findByIdAndOrderIdAndBuyerCode(
                        required(request.packingLineId(), AppMessages.PACKING_LIST_ROW_REQUIRED), order.getId(), buyer)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.PACKING_LIST_ROW_NOT_FOUND));
        if (!barcodeMatches(normalizeBarcode(barcode), line.getArticleNumber())) {
            throw new IllegalArgumentException(AppMessages.scannedBarcodeArticleMismatch(line.getArticleNumber()));
        }

        int planned = plannedCartons(line);
        long alreadyCounted = transactionRepository.countByPackingLineIdAndStatusIn(line.getId(), CartonScanStatus.countedStatuses());
        if (alreadyCounted >= planned) {
            throw new IllegalArgumentException(AppMessages.packingListRowComplete(alreadyCounted, planned));
        }

        LocalDateTime now = LocalDateTime.now();
        CartonScanTransaction entity = new CartonScanTransaction();
        entity.setJobId(nextCounter("carton_loading_job"));
        entity.setBuyerCode(buyer);
        entity.setOrderId(order.getId());
        entity.setOrderName(order.getOrderName());
        entity.setPackingLineId(line.getId());
        entity.setPackingLineNo(line.getLineNo());
        entity.setStationCode(stationCode);
        entity.setPalletCode(clean(request.palletCode()));
        entity.setBarcode(barcode.trim());
        entity.setPoNumber(line.getPoNumber());
        entity.setArticleNumber(line.getArticleNumber());
        entity.setStyleNumber(line.getStyleNumber());
        entity.setStyle(line.getStyle());
        entity.setColor(line.getColor());
        entity.setSize(line.getSize());
        entity.setQtyPerCarton(line.getQtyPerCarton());
        entity.setCartonPcs(line.getQtyPerCarton());
        long allocatedSequence = nextCounter("carton_line_sequence_" + line.getId());
        if (allocatedSequence > planned) {
            throw new IllegalArgumentException(AppMessages.packingListNoRemainingSlot(alreadyCounted, planned));
        }
        entity.setCartonSequence(Math.toIntExact(allocatedSequence));
        entity.setPlannedCartons(planned);
        entity.setItemSequence(Math.toIntExact(allocatedSequence));
        entity.setItemTotal(planned);
        entity.setItemKey(buildItemKey(line.getPoNumber(), line.getArticleNumber(), Math.toIntExact(allocatedSequence), planned));
        entity.setCartonCode(entity.getItemKey());
        entity.setExpectedWeightKg(line.getGrossWeightKg());
        entity.setWeightToleranceKg(defaultTolerance(station));
        entity.setWeightStatus(WeightStatus.NOT_WEIGHED.name());
        entity.setStatus(CartonScanStatus.WAITING_WEIGHT);
        entity.setScannedBy(RequestActor.current());
        entity.setScannedAt(now);
        entity.setUpdatedAt(now);

        try {
            CartonScanTransaction saved = transactionRepository.save(entity);
            socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.WAITING_WEIGHT, saved.getId());
            return saved;
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException(AppMessages.ANOTHER_CARTON_JOB_CREATED);
        }
    }

    public CartonScanTransaction current(String buyerCode, String stationCode) {
        String buyer = requireBuyer(buyerCode);
        return transactionRepository
                .findFirstByStationCodeAndStatusOrderByScannedAtDesc(normalizeStation(stationCode), CartonScanStatus.WAITING_WEIGHT)
                .filter(transaction -> buyer.equals(transaction.getBuyerCode()))
                .orElse(null);
    }

    public PlcJobResponse currentPlcJob(String stationCode) {
        ScaleStation station = stationService.requireActive(stationCode);
        Optional<CartonScanTransaction> open = transactionRepository
                .findFirstByStationCodeAndStatusOrderByScannedAtDesc(station.getStationCode(), CartonScanStatus.WAITING_WEIGHT);
        return open.map(job -> new PlcJobResponse(
                        station.getStationCode(), true, job.getJobId(), job.getId(), job.getArticleNumber(),
                        job.getCartonSequence(), job.getPlannedCartons(), station.getMinimumWeightKg()))
                .orElseGet(() -> new PlcJobResponse(
                        station.getStationCode(), false, null, null, null, null, null, station.getMinimumWeightKg()));
    }

    public CartonScanTransaction get(String buyerCode, String transactionId) {
        String buyer = requireBuyer(buyerCode);
        return transactionRepository.findByIdAndBuyerCode(transactionId, buyer)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.ITEM_TRANSACTION_NOT_FOUND));
    }

    public CartonScanTransaction receivePlcWeight(PlcWeightRequest request) {
        String stationCode = normalizeStation(request.stationCode());
        ScaleStation station = stationService.requireActive(stationCode);
        stationService.markOnline(stationCode, "Weight data received");
        if (!Boolean.TRUE.equals(request.stable())) {
            throw new IllegalArgumentException(AppMessages.SCALE_VALUE_NOT_STABLE);
        }
        BigDecimal weight = requireWeight(request.weightKg(), station);

        CartonScanTransaction transaction;
        if (request.jobId() != null) {
            transaction = transactionRepository.findByJobId(request.jobId())
                    .orElseThrow(() -> new IllegalArgumentException(AppMessages.plcJobNotFound(request.jobId())));
            if (!stationCode.equals(transaction.getStationCode())) {
                throw new IllegalArgumentException(AppMessages.plcJobWrongStation(stationCode));
            }
            if (transaction.getStatus() != CartonScanStatus.WAITING_WEIGHT) {
                if (transaction.getWeightKg() != null) return transaction;
                throw new IllegalArgumentException(AppMessages.PLC_JOB_NOT_WAITING);
            }
        } else {
            transaction = transactionRepository
                    .findFirstByStationCodeAndStatusOrderByScannedAtDesc(stationCode, CartonScanStatus.WAITING_WEIGHT)
                    .orElseThrow(() -> new IllegalArgumentException(AppMessages.noItemWaitingAtStation(stationCode)));
        }
        return completeWeight(transaction, weight, request.messageId(), AppConstants.SystemActor.PLC_GATEWAY, null, WeightSource.PLC.name());
    }

    public CartonScanTransaction manualWeight(String buyerCode, String transactionId, ManualWeightRequest request) {
        CartonScanTransaction transaction = get(buyerCode, transactionId);
        if (transaction.getStatus() != CartonScanStatus.WAITING_WEIGHT) {
            throw new IllegalArgumentException(AppMessages.TRANSACTION_NOT_WAITING);
        }
        ScaleStation station = blank(transaction.getStationCode())
                ? null
                : stationService.requireActive(transaction.getStationCode());
        BigDecimal weight = station == null
                ? requirePositiveWeight(request.weightKg())
                : requireWeight(request.weightKg(), station);
        String reason = required(request.reason(), AppMessages.MANUAL_INPUT_REASON_REQUIRED);
        return completeWeight(transaction, weight, "MANUAL-" + System.currentTimeMillis(), RequestActor.current(), reason, WeightSource.MANUAL.name());
    }

    public CartonProgressResponse progress(String buyerCode, String orderId) {
        String buyer = requireBuyer(buyerCode);
        orderService.getEntity(buyer, orderId);
        List<CartonScanTransaction> transactions = transactionRepository.findByOrderIdAndBuyerCode(orderId, buyer);

        long planned;
        if (!transactions.isEmpty()) {
            planned = transactions.stream().filter(t -> t.getStatus() != CartonScanStatus.CANCELLED).count();
        } else {
            List<PackingAllocationLine> masterLines = masterRepository.findByOrderIdAndBuyerCode(orderId, buyer);
            planned = masterLines.stream().mapToLong(this::masterTotalCartons).sum();
        }
        long completed = transactions.stream().filter(t -> t.getStatus() == CartonScanStatus.COMPLETED).count();
        long warning = transactions.stream().filter(t -> t.getStatus() == CartonScanStatus.WEIGHT_WARNING).count();
        long waiting = transactions.stream().filter(t -> t.getStatus() == CartonScanStatus.WAITING_WEIGHT).count();
        BigDecimal totalWeight = transactions.stream()
                .filter(t -> CartonScanStatus.isCompleted(t.getStatus()))
                .map(CartonScanTransaction::getWeightKg)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long remaining = Math.max(0, planned - completed - warning - waiting);
        return new CartonProgressResponse(orderId, planned, completed, waiting, warning, remaining, totalWeight);
    }

    public List<CartonScanTransaction> recent(String buyerCode, String orderId) {
        String buyer = requireBuyer(buyerCode);
        orderService.getEntity(buyer, orderId);
        return transactionRepository.findTop20ByOrderIdAndBuyerCodeOrderByScannedAtDesc(orderId, buyer).stream()
                .filter(item -> item.getScannedAt() != null)
                .toList();
    }

    private CartonScanTransaction completeWeight(
            CartonScanTransaction transaction,
            BigDecimal weight,
            String messageId,
            String actor,
            String manualReason,
            String weightSource
    ) {
        LocalDateTime now = LocalDateTime.now();
        BigDecimal actual = weight.setScale(3, RoundingMode.HALF_UP);
        transaction.setWeightKg(actual);
        transaction.setPlcMessageId(clean(messageId));
        transaction.setManualReason(clean(manualReason));
        transaction.setWeightSource(clean(weightSource));
        requireCartonShipmentReady(transaction);
        applyWeightStatus(transaction, actual);
        transaction.setWeighedBy(actor);
        transaction.setWeighedAt(now);
        transaction.setUpdatedAt(now);
        Update update = new Update().set("weightKg", actual).set("plcMessageId", clean(messageId))
                .set("manualReason", clean(manualReason)).set("weightSource", clean(weightSource))
                .set("weightDifferenceKg", transaction.getWeightDifferenceKg()).set("weightStatus", transaction.getWeightStatus())
                .set("status", transaction.getStatus()).set("warningMessage", transaction.getWarningMessage())
                .set("weighedBy", actor).set("weighedAt", now).set("updatedAt", now)
                .set("inspectedBy", actor).set("inspectedAt", now);
        CartonScanTransaction saved = mongoTemplate.findAndModify(Query.query(Criteria.where("_id").is(transaction.getId())
                .and("status").is(CartonScanStatus.WAITING_WEIGHT).and("jobId").is(transaction.getJobId())
                .and("completedAt").is(null).and("shippedAt").is(null)), update,
                FindAndModifyOptions.options().returnNew(true), CartonScanTransaction.class);
        if (saved == null) throw new IllegalArgumentException(AppMessages.WEIGHT_JOB_CHANGED);
        socketPublisher.cartonLoadingChanged(saved.getStatus().name(), saved.getId());
        return saved;
    }

    private CartonMasterItemResponse masterItemSummary(List<CartonScanTransaction> rows) {
        CartonScanTransaction first = rows.get(0);
        long notScanned = rows.stream().filter(row -> row.getStatus() == CartonScanStatus.PLANNED).count();
        long waiting = rows.stream().filter(row -> row.getStatus() == CartonScanStatus.WAITING_WEIGHT).count();
        long completed = rows.stream().filter(row -> CartonScanStatus.isCompleted(row.getStatus())).count();
        BigDecimal totalWeight = rows.stream()
                .filter(row -> CartonScanStatus.isCompleted(row.getStatus()))
                .map(CartonScanTransaction::getWeightKg)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartonMasterItemResponse(
                first.getMasterLineId(), first.getPackingLineNo(), first.getSupplierNumber(), first.getPoNumber(), first.getArticleNumber(),
                first.getStyleNumber(), first.getStyle(), first.getColor(), first.getSize(), first.getQtyPerCarton(),
                rows.size(), notScanned, waiting, completed, totalWeight,
                rows.get(0).getItemKey(), rows.get(rows.size() - 1).getItemKey()
        );
    }

    private CartonCandidateResponse candidate(PackingListLine line) {
        long counted = transactionRepository.countByPackingLineIdAndStatusIn(line.getId(), CartonScanStatus.countedStatuses());
        long waiting = transactionRepository.countByPackingLineIdAndStatusIn(line.getId(), List.of(CartonScanStatus.WAITING_WEIGHT));
        long completed = Math.max(0, counted - waiting);
        return new CartonCandidateResponse(
                line.getId(), line.getLineNo(), line.getPoNumber(), line.getArticleNumber(),
                line.getStyleNumber(), line.getStyle(), line.getColor(), line.getSize(),
                line.getQtyPerCarton(), plannedCartons(line), completed, waiting
        );
    }

    private PackingAllocationLine findMasterForPackingLine(
            List<PackingAllocationLine> masterLines,
            PackingListLine line
    ) {
        if (masterLines == null || line == null) return null;
        if (!blank(line.getMasterLineId())) {
            PackingAllocationLine linked = masterLines.stream().filter(m -> line.getMasterLineId().equals(m.getId())).findFirst().orElse(null);
            if (linked != null) return linked;
        }
        return masterLines.stream()
                .filter(master -> blank(line.getPoNumber()) || sameKey(line.getPoNumber(), master.getPoNumber()))
                .filter(master -> blank(line.getArticleNumber()) || sameKey(line.getArticleNumber(), master.getArticleNumber()))
                .filter(master -> blank(line.getStyleNumber()) || blank(master.getStyleNumber()) || sameKey(line.getStyleNumber(), master.getStyleNumber()))
                .filter(master -> blank(line.getStyle()) || blank(master.getStyle()) || sameKey(line.getStyle(), master.getStyle()))
                .filter(master -> blank(line.getColor()) || blank(master.getColor()) || sameKey(line.getColor(), master.getColor()))
                .filter(master -> blank(line.getSize()) || blank(master.getSize()) || sameKey(line.getSize(), master.getSize()))
                .sorted(Comparator.comparing(PackingAllocationLine::getLineNo, Comparator.nullsLast(Integer::compareTo)))
                .findFirst()
                .orElse(null);
    }

    /** Physical-carton count for a Master row. Total ctns is authoritative. */
    private int masterTotalCartons(PackingAllocationLine master) {
        if (master == null || !positive(master.getTotalCartons())) return 0;
        return Math.max(1, master.getTotalCartons().setScale(0, RoundingMode.HALF_UP).intValue());
    }

    private List<PackingListLine> packingLinesForMaster(
            List<PackingListLine> packingLines,
            PackingAllocationLine master
    ) {
        if (packingLines == null || packingLines.isEmpty() || master == null) return List.of();
        List<PackingListLine> exact = packingLines.stream()
                .filter(line -> !blank(line.getMasterLineId()) && master.getId().equals(line.getMasterLineId()))
                .sorted(Comparator.comparing(PackingListLine::getLineNo, Comparator.nullsLast(Integer::compareTo)))
                .toList();
        if (!exact.isEmpty()) return exact;
        return packingLines.stream()
                .filter(line -> blank(line.getPoNumber()) || sameKey(line.getPoNumber(), master.getPoNumber()))
                .filter(line -> blank(line.getArticleNumber()) || sameKey(line.getArticleNumber(), master.getArticleNumber()))
                .filter(line -> blank(line.getStyleNumber()) || blank(master.getStyleNumber()) || sameKey(line.getStyleNumber(), master.getStyleNumber()))
                .filter(line -> blank(line.getStyle()) || blank(master.getStyle()) || sameKey(line.getStyle(), master.getStyle()))
                .filter(line -> blank(line.getColor()) || blank(master.getColor()) || sameKey(line.getColor(), master.getColor()))
                .filter(line -> blank(line.getSize()) || blank(master.getSize()) || sameKey(line.getSize(), master.getSize()))
                .sorted(Comparator.comparing(PackingListLine::getLineNo, Comparator.nullsLast(Integer::compareTo)))
                .toList();
    }

    /**
     * Selects optional Packing List enrichment for one Master-driven physical carton.
     * Packing List CTNS Qty is used only to map metadata/weight onto a sequence; it never controls row count.
     */
    private PackingListLine packingLineForMasterSequence(List<PackingListLine> lines, int cartonSequence) {
        if (lines == null || lines.isEmpty() || cartonSequence <= 0) return null;
        int cumulative = 0;
        for (PackingListLine line : lines) {
            cumulative += plannedCartons(line);
            if (cartonSequence <= cumulative) return line;
        }
        return null;
    }

    private Integer cartonNumberForMasterSequence(
            List<PackingListLine> lines,
            int cartonSequence,
            int fallbackOrderSequence
    ) {
        if (lines == null || lines.isEmpty() || cartonSequence <= 0) return fallbackOrderSequence;
        int cumulative = 0;
        for (PackingListLine line : lines) {
            int count = plannedCartons(line);
            int previous = cumulative;
            cumulative += count;
            if (cartonSequence <= cumulative) {
                Integer first = integerCartonNumber(line.getCartonFrom());
                return first == null ? fallbackOrderSequence : first + (cartonSequence - previous) - 1;
            }
        }
        return fallbackOrderSequence;
    }

    private BigDecimal resolvedQtyPerCarton(PackingListLine line, PackingAllocationLine master) {
        if (line != null && positive(line.getQtyPerCarton())) return line.getQtyPerCarton();
        return master != null && positive(master.getQtyPerCarton()) ? master.getQtyPerCarton() : null;
    }

    private String cartonParentKey(PackingAllocationLine master, PackingListLine line) {
        if (master != null && !blank(master.getId())) return "MASTER:" + master.getId();
        return "PACKING:" + (line == null || blank(line.getId()) ? String.valueOf(line == null ? 0 : line.getLineNo()) : line.getId());
    }

    private Integer integerCartonNumber(BigDecimal value) {
        if (value == null) return null;
        try {
            return value.setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (ArithmeticException ex) {
            return value.setScale(0, RoundingMode.HALF_UP).intValue();
        }
    }

    private String firstText(String primary, String fallback) {
        String value = clean(primary);
        return value == null ? clean(fallback) : value;
    }

    private int plannedCartons(PackingListLine line) {
        if (line.getCartonsQty() != null && line.getCartonsQty().signum() > 0) {
            return Math.max(1, line.getCartonsQty().setScale(0, RoundingMode.HALF_UP).intValue());
        }
        if (line.getCartonFrom() != null && line.getCartonTo() != null) {
            BigDecimal calculated = line.getCartonTo().subtract(line.getCartonFrom()).add(BigDecimal.ONE);
            if (calculated.signum() > 0) return Math.max(1, calculated.setScale(0, RoundingMode.HALF_UP).intValue());
        }
        return 1;
    }

    private BigDecimal resolveCartonPcs(
            BigDecimal qtyPerCarton,
            BigDecimal remainingPcs,
            int sequence,
            int totalCartons
    ) {
        if (remainingPcs != null && remainingPcs.signum() > 0) {
            if (qtyPerCarton != null && qtyPerCarton.signum() > 0 && sequence < totalCartons) {
                return remainingPcs.min(qtyPerCarton);
            }
            return remainingPcs;
        }
        return qtyPerCarton;
    }

    private String itemGroupKey(String poNumber, String articleNumber) {
        return keyToken(poNumber) + "|" + keyToken(articleNumber);
    }

    private String buildItemKey(String poNumber, String articleNumber, int sequence, int total) {
        int width = Math.max(3, String.valueOf(Math.max(1, total)).length());
        return keyToken(poNumber) + "-" + keyToken(articleNumber) + "-"
                + String.format(Locale.ROOT, "%0" + width + "d", sequence);
    }

    private String keyToken(String value) {
        String token = value == null ? "NA" : value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return token.isEmpty() ? "NA" : token;
    }

    private BigDecimal findExpectedItemWeight(List<PackingListLine> packingLines, PackingAllocationLine master) {
        return packingLines.stream()
                .filter(line -> sameKey(line.getPoNumber(), master.getPoNumber()))
                .filter(line -> sameKey(line.getArticleNumber(), master.getArticleNumber()))
                .filter(line -> blank(master.getSize()) || blank(line.getSize()) || sameKey(line.getSize(), master.getSize()))
                .filter(line -> blank(master.getColor()) || blank(line.getColor()) || sameKey(line.getColor(), master.getColor()))
                .map(line -> {
                    if (positive(line.getNetWeightKg()) && positive(line.getTotalPcs())) {
                        return line.getNetWeightKg().divide(line.getTotalPcs(), 3, RoundingMode.HALF_UP);
                    }
                    if (positive(line.getGrossWeightKg()) && positive(line.getQtyPerCarton())) {
                        return line.getGrossWeightKg().divide(line.getQtyPerCarton(), 3, RoundingMode.HALF_UP);
                    }
                    return null;
                })
                .filter(this::positive)
                .findFirst()
                .orElse(null);
    }

    private boolean sameKey(String left, String right) {
        return keyToken(left).equals(keyToken(right));
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private BigDecimal defaultTolerance(ScaleStation station) {
        BigDecimal value = station == null ? null : station.getStabilityToleranceKg();
        return value == null || value.signum() < 0 ? new BigDecimal("0.050") : value;
    }

    private void applyWeightStatus(CartonScanTransaction transaction, BigDecimal actual) {
        BigDecimal expected = transaction.getExpectedWeightKg();
        if (expected == null || expected.signum() <= 0) {
            transaction.setWeightDifferenceKg(null);
            transaction.setWeightStatus(WeightStatus.NO_STANDARD.name());
            transaction.setStatus(CartonScanStatus.COMPLETED);
            transaction.setWarningMessage(null);
            return;
        }
        BigDecimal difference = actual.subtract(expected).setScale(3, RoundingMode.HALF_UP);
        BigDecimal tolerance = transaction.getWeightToleranceKg();
        if (tolerance == null || tolerance.signum() < 0) tolerance = new BigDecimal("0.050");
        transaction.setWeightDifferenceKg(difference);
        if (difference.abs().compareTo(tolerance) <= 0) {
            transaction.setWeightStatus(WeightStatus.OK.name());
            transaction.setStatus(CartonScanStatus.COMPLETED);
            transaction.setWarningMessage(null);
        } else if (difference.signum() < 0) {
            transaction.setWeightStatus(WeightStatus.UNDER.name());
            transaction.setStatus(CartonScanStatus.WEIGHT_WARNING);
            transaction.setWarningMessage("Under by " + difference.abs().toPlainString() + " kg");
        } else {
            transaction.setWeightStatus(WeightStatus.OVER.name());
            transaction.setStatus(CartonScanStatus.WEIGHT_WARNING);
            transaction.setWarningMessage("Over by " + difference.toPlainString() + " kg");
        }
    }

    private boolean qrMatchesItem(ParsedQr parsed, String normalizedBarcode, CartonScanTransaction row) {
        if (parsed.hasBusinessData()) {
            if (parsed.poNumber() != null && !sameKey(parsed.poNumber(), row.getPoNumber())) return false;
            if (parsed.articleNumber() != null && !sameKey(parsed.articleNumber(), row.getArticleNumber())) return false;
            if (parsed.supplierNumber() != null && !sameKey(parsed.supplierNumber(), row.getSupplierNumber())) return false;
            if (parsed.quantity() != null && row.getQtyPerCarton() != null
                    && parsed.quantity().compareTo(row.getQtyPerCarton()) != 0) return false;
            return parsed.poNumber() != null || parsed.articleNumber() != null;
        }
        return barcodeMatches(normalizedBarcode, row.getArticleNumber())
                || barcodeMatches(normalizedBarcode, row.getPoNumber())
                || normalizeBarcode(row.getItemKey()).equals(normalizedBarcode);
    }

    private ParsedQr parseQr(String raw) {
        String value = raw == null ? "" : raw.trim();

        /*
         * Actual Engelbert Strauss carton QR format from the supplied label:
         * 0000000000008589132-0000000040-0001571490-00000000000000000000
         * ARTICLE (19) - QTY (10) - PO (10) - reserved (20)
         */
        if (value.matches("\\d{19}-\\d{10}-\\d{10}-\\d{20}")) {
            String[] parts = value.split("-");
            return new ParsedQr(
                    null,
                    stripLeadingZeros(parts[2]),
                    stripLeadingZeros(parts[0]),
                    decimal(stripLeadingZeros(parts[1]))
            );
        }

        Map<String, String> fields = new HashMap<>();
        for (String token : value.split("[|;\n\r]+")) {
            String[] pair = token.split("[:=]", 2);
            if (pair.length == 2) fields.put(normalizeLabel(pair[0]), pair[1].trim());
        }
        String supplier = first(fields, "SUPPLIERNO", "SUPPLIER", "ESSUPPLIER", "LIEFERANT");
        String po = first(fields, "ORDERNO", "ORDER", "PONO", "PO", "ESPO", "AUFTRAGSNR", "AUFTRAG");
        String article = first(fields, "ARTICLENO", "ARTICLE", "ART", "ESARTICLE", "ARTIKELNR", "ARTIKEL");
        BigDecimal quantity = decimal(first(fields, "QUANTITY", "QTY", "ANZAHL", "QTYPERCTN"));
        if (supplier == null && po == null && article == null && value.contains("|")) {
            String[] parts = value.split("\\|");
            if (parts.length >= 4 && parts[0].trim().matches("[A-Za-z0-9._/-]+")) {
                supplier = parts[0].trim();
                po = parts[1].trim();
                article = parts[2].trim();
                quantity = decimal(parts[3]);
            }
        }
        return new ParsedQr(supplier, po, article, quantity);
    }


    private String stripLeadingZeros(String value) {
        if (value == null) return null;
        String stripped = value.trim().replaceFirst("^0+(?!$)", "");
        return stripped.isEmpty() ? "0" : stripped;
    }

    private String normalizeLabel(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private String first(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.replace(",", ".").replaceAll("[^0-9.-]", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    private record ParsedQr(String supplierNumber, String poNumber, String articleNumber, BigDecimal quantity) {
        boolean hasBusinessData() {
            return supplierNumber != null || poNumber != null || articleNumber != null || quantity != null;
        }
    }

    private boolean hasOperationalData(CartonScanTransaction row) {
        return row.getShipmentId() != null || row.getCompletedAt() != null || row.getShippedAt() != null
                || row.getStatus() == CartonScanStatus.WAITING_WEIGHT
                || row.getStatus() == CartonScanStatus.COMPLETED
                || row.getStatus() == CartonScanStatus.WEIGHT_WARNING
                || row.getJobId() != null
                || row.getWeightKg() != null
                || !blank(row.getFactoryBarcode());
    }

    private Criteria assignmentCriteria(String value) {
        String clean = clean(value);
        if (clean == null || AppConstants.Filter.ALL.equalsIgnoreCase(clean)) return null;
        if (AppConstants.Filter.ASSIGNED.equalsIgnoreCase(clean)) return assignedBarcodeCriteria();
        if (AppConstants.Filter.UNASSIGNED.equalsIgnoreCase(clean)) return unassignedBarcodeCriteria();
        throw new IllegalArgumentException(AppMessages.unsupportedAssignmentFilter(value));
    }

    private Criteria assignedBarcodeCriteria() {
        return new Criteria().andOperator(
                Criteria.where("factoryBarcode").exists(true),
                Criteria.where("factoryBarcode").ne(null),
                Criteria.where("factoryBarcode").ne("")
        );
    }

    private Criteria unassignedBarcodeCriteria() {
        return new Criteria().orOperator(
                Criteria.where("factoryBarcode").exists(false),
                Criteria.where("factoryBarcode").is(null),
                Criteria.where("factoryBarcode").is("")
        );
    }

    /**
     * Builds a MongoDB-side keyword filter so large Orders are never loaded into JVM memory merely
     * to search them. The Order/Buyer equality criteria are applied separately and use the compound
     * Order indexes before this filter is evaluated.
     */
    private Criteria cartonKeywordCriteria(String value) {
        String clean = clean(value);
        if (clean == null) return null;
        Pattern pattern = Pattern.compile(Pattern.quote(clean), Pattern.CASE_INSENSITIVE);
        List<Criteria> alternatives = new ArrayList<>();
        for (String field : List.of(
                "factoryBarcode",
                "cartonCode",
                "itemKey",
                "poNumber",
                "articleNumber",
                "styleNumber",
                "style",
                "color",
                "size",
                "supplierNumber"
        )) {
            alternatives.add(Criteria.where(field).regex(pattern));
        }
        try {
            int number = Integer.parseInt(clean);
            alternatives.add(Criteria.where("cartonNumber").is(number));
            alternatives.add(Criteria.where("cartonSequence").is(number));
            alternatives.add(Criteria.where("orderCartonSequence").is(number));
        } catch (NumberFormatException ignored) {
            // Normal text search.
        }
        return new Criteria().orOperator(alternatives.toArray(new Criteria[0]));
    }

    /**
     * Weight Check may start only after the Order reaches 100% barcode assignment.
     * Once at least one carton has entered Weight Check, the Order is no longer used as a global lock:
     * other already-assigned cartons may continue to be weighed while an unweighed carton is corrected/reassigned.
     */
    public void requireCartonShipmentReady(CartonScanTransaction carton) {
        Shipment shipment = blank(carton.getShipmentId()) ? null
                : mongoTemplate.findById(carton.getShipmentId(), Shipment.class);
        boolean member = shipment != null && carton.getBuyerCode().equals(shipment.getBuyerCode())
                && carton.getOrderId().equals(shipment.getOrderId()) && shipment.getCartonIds() != null
                && shipment.getCartonIds().contains(carton.getId());
        ShipmentWorkflowPolicy.requirePlanned(shipment == null ? null : shipment.getStatus(),
                !blank(carton.getFactoryBarcode()), member);
        Shipment claimed = mongoTemplate.findAndModify(Query.query(Criteria.where("_id").is(shipment.getId())
                        .and("buyerCode").is(carton.getBuyerCode()).and("status").is(ShipmentStatus.PLANNED.name())),
                new Update().set("executionStarted", true), FindAndModifyOptions.options().returnNew(true), Shipment.class);
        if (claimed == null) throw new IllegalArgumentException(AppMessages.SHIPMENT_PLAN_CHANGED);
    }

    public void requireWeightStageReady(String buyer, String orderId) {
        List<CartonScanTransaction> cartons = transactionRepository.findByOrderIdAndBuyerCode(orderId, buyer).stream()
                .filter(row -> row.getStatus() != CartonScanStatus.CANCELLED)
                .toList();
        if (cartons.isEmpty()) {
            throw new IllegalArgumentException(AppMessages.NO_PHYSICAL_CARTONS);
        }

        boolean weightAlreadyStarted = cartons.stream().anyMatch(this::hasWeightStarted);
        if (weightAlreadyStarted) return;

        long assigned = cartons.stream().filter(row -> !blank(row.getFactoryBarcode())).count();
        if (assigned < cartons.size()) {
            throw new IllegalArgumentException(
                    AppMessages.weightCheckRequiresBarcodeAssignment(assigned, cartons.size())
            );
        }
    }

    private boolean hasWeightStarted(CartonScanTransaction row) {
        return row != null && (row.getStatus() == CartonScanStatus.WAITING_WEIGHT
                || row.getStatus() == CartonScanStatus.COMPLETED
                || row.getStatus() == CartonScanStatus.WEIGHT_WARNING
                || row.getJobId() != null
                || row.getWeightKg() != null
                || row.getScannedAt() != null
                || row.getWeighedAt() != null);
    }

    private CartonScanStatus parseStatus(String value) {
        String clean = clean(value);
        if (clean == null || AppConstants.Filter.ALL.equalsIgnoreCase(clean)) return null;
        try {
            return CartonScanStatus.valueOf(clean.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(AppMessages.unsupportedItemStatus(value));
        }
    }

    private boolean matchesCartonKeyword(CartonScanTransaction row, String keyword) {
        return containsIgnoreCase(row.getItemKey(), keyword)
                || containsIgnoreCase(row.getCartonCode(), keyword)
                || (row.getCartonNumber() != null && String.valueOf(row.getCartonNumber()).contains(keyword))
                || containsIgnoreCase(row.getFactoryBarcode(), keyword)
                || containsIgnoreCase(row.getPoNumber(), keyword)
                || containsIgnoreCase(row.getArticleNumber(), keyword)
                || containsIgnoreCase(row.getStyleNumber(), keyword)
                || containsIgnoreCase(row.getStyle(), keyword)
                || containsIgnoreCase(row.getColor(), keyword)
                || containsIgnoreCase(row.getSize(), keyword);
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toUpperCase(Locale.ROOT).contains(keyword);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private BigDecimal requirePositiveWeight(BigDecimal weight) {
        if (weight == null || weight.signum() <= 0) {
            throw new IllegalArgumentException(AppMessages.WEIGHT_GREATER_THAN_ZERO);
        }
        return weight;
    }

    private BigDecimal requireWeight(BigDecimal weight, ScaleStation station) {
        if (weight == null || weight.signum() <= 0) throw new IllegalArgumentException(AppMessages.WEIGHT_GREATER_THAN_ZERO);
        BigDecimal minimum = station.getMinimumWeightKg() == null ? new BigDecimal("0.50") : station.getMinimumWeightKg();
        if (weight.compareTo(minimum) < 0) {
            throw new IllegalArgumentException(AppMessages.weightBelowStationMinimum(minimum));
        }
        return weight;
    }

    private long nextCounter(String counterId) {
        Query query = Query.query(Criteria.where("_id").is(counterId));
        Update update = new Update().inc("value", 1L);
        org.bson.Document counter = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true).upsert(true),
                org.bson.Document.class,
                "system_counters"
        );
        Number value = counter == null ? null : (Number) counter.get("value");
        if (value == null) throw new IllegalStateException(AppMessages.UNABLE_ALLOCATE_CARTON_JOB);
        return value.longValue();
    }

    private boolean barcodeMatches(String normalizedBarcode, String articleNumber) {
        String article = normalizeBarcode(articleNumber);
        if (article.isEmpty() || normalizedBarcode.isEmpty()) return false;
        return normalizedBarcode.equals(article)
                || normalizedBarcode.endsWith(article)
                || tokenizedContains(normalizedBarcode, article);
    }

    private boolean tokenizedContains(String barcode, String article) {
        if (barcode.length() < article.length()) return false;
        return barcode.contains("ARTICLE" + article) || barcode.contains("ART" + article);
    }

    private String normalizeBarcode(String value) {
        if (value == null) return "";
        String clean = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (clean.matches("\\d+")) clean = clean.replaceFirst("^0+(?!$)", "");
        return clean;
    }

    private String requireBuyer(String value) {
        String buyer = BuyerAccess.normalize(value);
        if (buyer.isEmpty()) throw new IllegalArgumentException(AppMessages.unsupportedBuyer(value));
        return buyer;
    }

    private String normalizeStation(String value) {
        return required(value, AppMessages.STATION_CODE_REQUIRED).trim().toUpperCase(Locale.ROOT);
    }

    private String required(String value, String message) {
        String clean = clean(value);
        if (clean == null) throw new IllegalArgumentException(message);
        return clean;
    }

    private String clean(String value) {
        if (value == null) return null;
        String clean = value.trim().replaceAll("\\s+", " ");
        return clean.isEmpty() ? null : clean;
    }
}
