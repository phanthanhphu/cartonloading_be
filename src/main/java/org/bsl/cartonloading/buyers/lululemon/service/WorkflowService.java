package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.constants.AppMessages;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.lululemon.dto.*;
import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.CartonItem;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.model.ScanEvent;
import org.bsl.cartonloading.buyers.lululemon.model.ShippingSchedule;
import org.bsl.cartonloading.buyers.lululemon.model.WeighingResult;
import org.bsl.cartonloading.buyers.lululemon.repository.CartonItemRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.CartonRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.PurchaseOrderRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.ScanEventRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.ShippingScheduleRepository;
import org.bsl.cartonloading.common.socket.AppSocketPublisher;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.buyers.es.service.PackingOrderService;
import org.bsl.cartonloading.service.RequestActor;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.bsl.cartonloading.common.importing.ExcelImportSupport;
import org.bsl.cartonloading.common.importing.TextNormalizer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class WorkflowService {

    private final PurchaseOrderRepository poRepository;
    private final CartonRepository cartonRepository;
    private final CartonItemRepository itemRepository;
    private final ScanEventRepository scanEventRepository;
    private final ShippingScheduleRepository shippingScheduleRepository;
    private final PackingOrderService orderService;
    private final ExcelImportSupport excelSupport;
    private final BuyerFactoryAccessService factoryAccess;
    private final MongoTemplate mongoTemplate;
    private final AuditLogService auditLogService;
    private final AppSocketPublisher socketPublisher;
    private final FactoryCatalogService factories;

    public WorkflowService(
            PurchaseOrderRepository poRepository,
            CartonRepository cartonRepository,
            CartonItemRepository itemRepository,
            ScanEventRepository scanEventRepository,
            ShippingScheduleRepository shippingScheduleRepository,
            PackingOrderService orderService,
            ExcelImportSupport excelSupport,
            BuyerFactoryAccessService factoryAccess,
            MongoTemplate mongoTemplate,
            AuditLogService auditLogService,
            AppSocketPublisher socketPublisher,
            FactoryCatalogService factories
    ) {
        this.poRepository = poRepository;
        this.cartonRepository = cartonRepository;
        this.itemRepository = itemRepository;
        this.scanEventRepository = scanEventRepository;
        this.shippingScheduleRepository = shippingScheduleRepository;
        this.orderService = orderService;
        this.excelSupport = excelSupport;
        this.factoryAccess = factoryAccess;
        this.mongoTemplate = mongoTemplate;
        this.auditLogService = auditLogService;
        this.socketPublisher = socketPublisher;
        this.factories = factories;
    }

    public ImportResult importAllBp(String buyerCode, String orderId, MultipartFile file, boolean replace) {
        return importAllBpInternal(buyerCode, orderId, file, replace, false);
    }

    /** Sales master file: add only new Master PO + PO pairs; never overwrite an existing pair. */
    public ImportResult importSharedAllBp(String buyerCode, String orderId, MultipartFile file) {
        return importAllBpInternal(buyerCode, orderId, file, false, true);
    }

    private synchronized ImportResult importAllBpInternal(String buyerCode, String orderId, MultipartFile file,
                                                           boolean replace, boolean sharedImport) {
        PackingOrder order = requireOrder(buyerCode, orderId);
        if (order.getFactoryCode() == null || order.getFactoryCode().isBlank())
            throw new IllegalArgumentException("Classify legacy Order Factory before ALL_BP import.");
        List<ParsedPo> parsed;
        List<String> warnings = new ArrayList<>();
        List<String> sourceHeaderLabels = List.of();

        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, AppConstants.Excel.ALL_BP_SHEET);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            int headerRowIndex = findHeaderRow(sheet, evaluator);
            Row header = sheet.getRow(headerRowIndex);
            Map<String, Integer> columns = resolveAllBpColumns(header, evaluator);
            List<SourceColumn> sourceColumns = sourceColumns(header, evaluator);
            sourceHeaderLabels = sourceColumns.stream().map(SourceColumn::label).toList();

            // All four identity columns are required: a missing header must not
            // silently turn every value in that column into the same empty key.
            requiredCanonicalColumn(columns, AppConstants.AllBpKey.MASTER_PO);
            int poCol = requiredCanonicalColumn(columns, AppConstants.AllBpKey.PO);
            requiredCanonicalColumn(columns, AppConstants.AllBpKey.PACKING_PLAN);
            requiredCanonicalColumn(columns, AppConstants.AllBpKey.SALES_ORDER_PTS);
            int styleCol = requiredCanonicalColumn(columns, AppConstants.AllBpKey.STYLE);
            int qtyCol = requiredCanonicalColumn(columns, AppConstants.AllBpKey.QTY);
            int pcsCol = requiredCanonicalColumn(columns, AppConstants.AllBpKey.PCS_CTN);
            Integer factoryCol = columns.get("FACTORY");
            boolean warnedMissingFactory = false;

            List<ParsedPo> sourcePos = new ArrayList<>();
            // Reject a repeated four-column identity anywhere in the same workbook,
            // irrespective of Style, Destination, Qty or other ALL_BP fields.
            Map<AllBpPoFourKeyGuard.Key, Integer> seenPoKeys = new LinkedHashMap<>();
            for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;
                String poNumber = clean(excelSupport.text(row, poCol, evaluator));
                if (poNumber == null) continue;

                String factory = factoryCol == null ? null : clean(excelSupport.text(row, factoryCol, evaluator));
                if (factory == null) factory = clean(order.getFactoryCode());
                factory = normalizeFactory(factory);
                if (factory == null) {
                    throw new IllegalArgumentException("Factory is required in ALL_BP or Order for PO " + poNumber
                            + " (row " + (rowIndex + 1) + "). Assign an active Factory before importing.");
                }
                factoryAccess.assertAssignedFactoryAccess(factory);
                if (!factory.equalsIgnoreCase(order.getFactoryCode()))
                    throw new IllegalArgumentException("ALL_BP row " + (rowIndex + 1) + " Factory " + factory
                            + " differs from Order Factory " + order.getFactoryCode() + ".");

                String style = clean(excelSupport.text(row, styleCol, evaluator));
                int totalQty = requiredPositiveInt(excelSupport.decimal(row, qtyCol, evaluator), "Q'ty", rowIndex + 1);
                int qtyPerCarton = requiredPositiveInt(excelSupport.decimal(row, pcsCol, evaluator), "Pcs/ctn", rowIndex + 1);
                int cartonCount = (totalQty + qtyPerCarton - 1) / qtyPerCarton;
                int remainder = totalQty % qtyPerCarton;

                ParsedPo candidate = new ParsedPo(
                        rowIndex + 1,
                        factory,
                        poNumber,
                        style,
                        totalQty,
                        qtyPerCarton,
                        cartonCount,
                        remainder,
                        cartonQuantities(totalQty, qtyPerCarton),
                        canonicalValue(row, columns, evaluator, AppConstants.AllBpKey.DC_CODE),
                        canonicalValue(row, columns, evaluator, AppConstants.AllBpKey.DESTINATION),
                        canonicalValue(row, columns, evaluator, AppConstants.AllBpKey.CHANNEL),
                        canonicalValue(row, columns, evaluator, AppConstants.AllBpKey.MASTER_PO),
                        canonicalValue(row, columns, evaluator, AppConstants.AllBpKey.PACKING_PLAN),
                        canonicalValue(row, columns, evaluator, AppConstants.AllBpKey.SALES_ORDER_PTS),
                        canonicalValue(row, columns, evaluator, AppConstants.AllBpKey.DESCRIPTION),
                        canonicalValue(row, columns, evaluator, AppConstants.AllBpKey.COLOR),
                        canonicalValue(row, columns, evaluator, AppConstants.AllBpKey.SIZE),
                        canonicalValue(row, columns, evaluator, AppConstants.AllBpKey.SHIP_MODE),
                        canonicalValue(row, columns, evaluator, AppConstants.AllBpKey.SEASON),
                        canonicalValue(row, columns, evaluator, AppConstants.AllBpKey.FWD),
                        canonicalValue(row, columns, evaluator, AppConstants.AllBpKey.CARTON_BOX_SIZE),
                        canonicalDecimalValue(row, columns, evaluator, "NW"),
                        canonicalDecimalValue(row, columns, evaluator, "GW"),
                        List.of(captureAllBpRow(row, sourceColumns, evaluator))
                );

                AllBpPoFourKeyGuard.checkFileRow(seenPoKeys, candidate.masterPo(),
                        candidate.poNumber(), candidate.packingPlan(), candidate.salesOrderPts(),
                        candidate.sourceLineNo());
                // No merging: duplicate four-column identities are a validation error.
                sourcePos.add(candidate);
            }
            parsed = sourcePos;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException(AppMessages.cannotImportForBuyer(AppConstants.Buyer.LULULEMON, AppConstants.ImportSource.ALL_BP, cleanMessage(ex)));
        }

        if (parsed.isEmpty()) throw new IllegalArgumentException(AppMessages.ALL_BP_EMPTY);
        // Preflight the complete file BEFORE any update/save. This includes Sales Shared
        // Import: an existing four-column identity is not a new PO, even if
        // other ALL_BP fields differ. The check happens before any database write.
        List<PurchaseOrder> savedPos = poRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(
                orderId, AppConstants.Buyer.LULULEMON);
        AllBpPoFourKeyGuard.checkAgainstSaved(savedPos, parsed.stream()
                .map(po -> AllBpPoFourKeyGuard.key(po.masterPo(), po.poNumber(),
                        po.packingPlan(), po.salesOrderPts()))
                .collect(Collectors.toSet()));
        // Both upload entry points may append new four-key identities to an
        // existing Order. Prior POs are never replaced by a regular upload;
        // collisions have already been rejected by the preflight above.
        if (replace && !sharedImport) {
            // Safety invariant: legacy Replace cannot destroy PO picks.
            boolean hasPicked = savedPos.stream()
                    .anyMatch(po -> po.getPickedBy() != null && !po.getPickedBy().isBlank());
            if (hasPicked) throw new IllegalArgumentException("Picked PO exists: use Sales shared import, not Replace.");
            assertReplaceAllowed(orderId);
        }

        // Safety first: legacy destructive Replace could delete valid scan/carton records
        // before a later save fails. Production re-imports must use the incremental
        // shared import path; it preserves all operational records.
        if (replace && !sharedImport && !savedPos.isEmpty()) {
            throw new IllegalArgumentException("Destructive All_BP Replace is disabled to protect carton/scan data. Use Sales Shared Import instead.");
        }

        LocalDateTime now = LocalDateTime.now();
        String actor = RequestActor.current();
        List<PurchaseOrder> poBatch = new ArrayList<>(parsed.size());
        Map<AllBpBusinessKey, PurchaseOrder> existing = new HashMap<>();
        if (sharedImport) {
            for (PurchaseOrder previous : savedPos) {
                PurchaseOrder duplicate = existing.putIfAbsent(AllBpBusinessKey.from(previous), previous);
                if (duplicate != null) throw new IllegalArgumentException("Duplicate existing PO business key: " + previous.getPoNumber());
            }
        }
        int createdCount = 0;
        int updatedCount = 0;
        for (ParsedPo parsedPo : parsed) {
            PurchaseOrder previous = sharedImport ? existing.get(AllBpBusinessKey.from(parsedPo)) : null;
            if (previous != null) {
                // Do not mutate carton plans after Cartons/Scans/RFID/Shipping activity.
                boolean busy = previous.getSku() != null && !previous.getSku().isBlank()
                        || !cartonRepository.findByPoIdOrderByCartonNoAsc(previous.getId()).isEmpty()
                        || shippingScheduleRepository.findByOrderIdAndBuyerCodeOrderByExFtyDateAscCreatedAtDesc(
                            orderId, AppConstants.Buyer.LULULEMON).stream().anyMatch(s -> s.getPoIds() != null && s.getPoIds().contains(previous.getId()));
                if (busy || !AppConstants.PurchaseOrderStatus.NOT_STARTED.equals(previous.getStatus())) {
                    warnings.add("PO " + parsedPo.poNumber() + " already has operational data; existing values and assignment were retained.");
                    continue;
                }
                // Only source columns are updated atomically. Crucially, never write the
                // imported snapshot over factoryCode/pickedBy/pickedAt during a concurrent Pick.
                Query sourceQuery = Query.query(Criteria.where("_id").is(previous.getId())
                        .and("orderId").is(orderId).and("buyerCode").is(AppConstants.Buyer.LULULEMON)
                        .and("status").is(AppConstants.PurchaseOrderStatus.NOT_STARTED).and("sku").is(null));
                Update sourceUpdate = new Update()
                        .set("buyer_key", AppConstants.Buyer.LULULEMON)
                        .set("sourceRowNumber", parsedPo.sourceLineNo())
                        .set("dcCode", parsedPo.dcCode()).set("destination", parsedPo.destination())
                        .set("channel", parsedPo.channel()).set("masterPo", parsedPo.masterPo())
                        .set("packingPlan", parsedPo.packingPlan()).set("salesOrderPts", parsedPo.salesOrderPts())
                        .set("style", parsedPo.styleNumber()).set("description", parsedPo.description())
                        .set("color", parsedPo.color()).set("size", parsedPo.size())
                        .set("plannedTotalQty", parsedPo.totalQty()).set("pcsPerCarton", parsedPo.qtyPerCarton())
                        .set("plannedCartons", parsedPo.cartonCount()).set("remainderQty", parsedPo.remainderQty())
                        .set("cartonPlannedQuantities", parsedPo.cartonPlannedQuantities())
                        .set("shipMode", parsedPo.shipMode()).set("season", parsedPo.season())
                        .set("fwd", parsedPo.fwd()).set("cartonBoxSize", parsedPo.cartonBoxSize())
                        .set("netWeightKg", parsedPo.netWeight()).set("grossWeightKg", parsedPo.grossWeight())
                        .set("allBpHeaders", sourceHeaderLabels).set("allBpRows", parsedPo.allBpRows())
                        .set("updatedBy", actor).set("updatedAt", now);
                if (mongoTemplate.updateFirst(sourceQuery, sourceUpdate, PurchaseOrder.class).getMatchedCount() == 0) {
                    warnings.add("PO " + parsedPo.poNumber() + " changed while importing; it was skipped.");
                } else updatedCount++;
                continue;
            }
            PurchaseOrder po = new PurchaseOrder();
            po.setBuyerCode(AppConstants.Buyer.LULULEMON);
            po.setOrderId(orderId);
            po.setSourceLineNo(parsedPo.sourceLineNo());
            // Imported Packing data always belongs to its source Factory; it never
            // inherits the user's future Factory selection.
            po.setFactoryCode(parsedPo.factoryCode());
            po.setBuyerKey(AppConstants.Buyer.LULULEMON);
            po.setDcCode(parsedPo.dcCode());
            po.setDestination(parsedPo.destination());
            po.setChannel(parsedPo.channel());
            po.setMasterPo(parsedPo.masterPo());
            po.setPoNumber(parsedPo.poNumber());
            po.setPackingPlan(parsedPo.packingPlan());
            po.setSalesOrderPts(parsedPo.salesOrderPts());
            po.setStyleNumber(parsedPo.styleNumber());
            po.setDescription(parsedPo.description());
            po.setColor(parsedPo.color());
            po.setSize(parsedPo.size());
            po.setTotalQty(parsedPo.totalQty());
            po.setQtyPerCarton(parsedPo.qtyPerCarton());
            po.setCartonCount(parsedPo.cartonCount());
            po.setRemainderQty(parsedPo.remainderQty());
            po.setCartonPlannedQuantities(List.copyOf(parsedPo.cartonPlannedQuantities()));
            po.setShipMode(parsedPo.shipMode());
            po.setSeason(parsedPo.season());
            po.setFwd(parsedPo.fwd());
            po.setCartonBoxSize(parsedPo.cartonBoxSize());
            po.setNetWeight(parsedPo.netWeight());
            po.setGrossWeight(parsedPo.grossWeight());
            po.setAllBpHeaders(List.copyOf(sourceHeaderLabels));
            po.setAllBpRows(List.copyOf(parsedPo.allBpRows()));
            po.setStatus(AppConstants.PurchaseOrderStatus.NOT_STARTED);
            po.setCreatedBy(actor);
            po.setCreatedAt(now);
            createdCount++;
            po.setUpdatedBy(actor);
            po.setUpdatedAt(now);
            poBatch.add(po);
        }
        poRepository.saveAll(poBatch);

        warnings.add("Lazy generation enabled: import creates PO records only. Cartons are generated when a PO is opened; Items are generated when a Carton is opened.");
        audit(AppConstants.AuditAction.ALL_BP_IMPORT, orderId,
                sharedImport ? "Imported " + createdCount + " new / updated " + updatedCount + " existing PO(s) from All_BP."
                        : "Imported " + parsed.size() + " PO(s) from All_BP. Carton/Item generation is deferred until the user opens them.");
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.ALL_BP_IMPORTED, orderId);
        return new ImportResult(true, createdCount, 0, 0, updatedCount, warnings);
    }

    public Page<PoSummary> listPos(String buyerCode, String orderId, String keyword, String status, int page, int size) {
        requireOrder(buyerCode, orderId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));

        Criteria criteria = Criteria.where("orderId").is(orderId).and("buyerCode").is(AppConstants.Buyer.LULULEMON);
        if (!factoryAccess.isUnrestricted()) {
            criteria = criteria.and("factoryCode").in(factoryAccess.accessibleFactories());
        }
        String statusKey = clean(status);
        if (statusKey != null && !"ALL".equalsIgnoreCase(statusKey)) {
            criteria = criteria.and("status").is(statusKey.toUpperCase(Locale.ROOT));
        }
        String key = clean(keyword);
        if (key != null) {
            String regex = ".*" + java.util.regex.Pattern.quote(key) + ".*";
            criteria = criteria.andOperator(new Criteria().orOperator(
                    Criteria.where("poNumber").regex(regex, "i"),
                    Criteria.where("style").regex(regex, "i"),
                    Criteria.where("sku").regex(regex, "i"),
                    Criteria.where("factoryCode").regex(regex, "i"),
                    Criteria.where("color").regex(regex, "i"),
                    Criteria.where("description").regex(regex, "i")
            ));
        }

        Query countQuery = Query.query(criteria);
        long total = mongoTemplate.count(countQuery, PurchaseOrder.class);
        Query dataQuery = Query.query(criteria)
                .with(Sort.by(Sort.Direction.ASC, "poNumber"))
                .skip((long) safePage * safeSize)
                .limit(safeSize);
        List<PoSummary> rows = mongoTemplate.find(dataQuery, PurchaseOrder.class).stream()
                .map(this::summary)
                .toList();
        Pageable pageable = PageRequest.of(safePage, safeSize);
        return new PageImpl<>(rows, pageable, total);
    }

    public PoDetailResponse getPo(String buyerCode, String orderId, String poId) {
        requireOrder(buyerCode, orderId);
        PurchaseOrder po = requirePo(orderId, poId);
        if (!canAccessFactoryOrUnassigned(po.getFactoryCode())
                && !factoryAccess.canAccessWeighingFactory(po.getFactoryCode())) {
            throw new org.springframework.security.access.AccessDeniedException("Factory access denied");
        }
        List<Carton> cartons = ensureCartonsGenerated(po);
        return new PoDetailResponse(po, cartons);
    }

    /**
     * Edit the imported PO source record. Cartons/Items are derived data and are never
     * directly edited here. ALL_BP formula columns are always recalculated on the backend.
     *
     * If generated Cartons/Items already existed (but no operation has started), their
     * structure is rebuilt from the edited PO immediately so stale generated data cannot
     * remain visible. If they had never been generated, lazy generation is preserved.
     */
    public synchronized PurchaseOrder updateImportedPo(
            String buyerCode, String orderId, String poId, PoUpdateRequest request
    ) {
        requireOrder(buyerCode, orderId);
        PurchaseOrder po = requirePo(orderId, poId);
        assertFactoryAccessIfPresent(po.getFactoryCode());
        // Picking does not lock Sales edits. The actual lock is completed weighing.
        assertPoWeighingNotCompleted(po);
        if (request == null) throw new IllegalArgumentException(AppMessages.PO_DATA_REQUIRED);

        // Retain operational identity and all carton/scan data after Packing or dispatch.
        // Non-structural ALL_BP metadata edits remain available until Weight completes.
        boolean picked = clean(po.getPickedBy()) != null;
        boolean operational = hasPoOperationalActivity(po) || isAssignedToAnySchedule(po)
                || po.getPlannedShippingDate() != null;
        String originalNumber = po.getPoNumber();
        String originalFactory = normalizeFactory(po.getFactoryCode());
        String originalStyle = po.getStyleNumber();
        String originalMasterPo = po.getMasterPo();
        String originalDc = po.getDcCode();
        String originalDestination = po.getDestination();
        String originalChannel = po.getChannel();
        String originalPackingPlan = po.getPackingPlan();
        String originalSalesOrderPts = po.getSalesOrderPts();
        String originalShipMode = po.getShipMode();
        String originalCartonBoxSize = po.getCartonBoxSize();
        Integer originalQty = po.getTotalQty();
        Integer originalPcs = po.getQtyPerCarton();
        List<Integer> originalPlan = po.getCartonPlannedQuantities() == null ? List.of() : List.copyOf(po.getCartonPlannedQuantities());
        String originalColor = po.getColor();
        String originalSize = po.getSize();
        Double originalNetWeight = po.getNetWeightKg();
        Double originalGrossWeight = po.getGrossWeightKg();
        LocalDate originalExFty = po.getExFtyDate();

        List<Carton> generatedBeforeEdit = cartonRepository.findByPoIdOrderByCartonNoAsc(poId);
        boolean hadGeneratedCartons = !generatedBeforeEdit.isEmpty();
        boolean hadGeneratedItems = generatedBeforeEdit.stream().anyMatch(carton -> itemRepository.existsByCartonId(carton.getId()));

        EditedAllBpPlan allBpPlan = buildEditedAllBpPlan(po, request);

        String factoryCode = normalizeFactory(request.factoryCode());
        if (factoryCode == null) factoryCode = normalizeFactory(po.getFactoryCode());
        PackingOrder owningOrder = requireOrder(buyerCode, orderId);
        if (factoryCode == null || !factoryCode.equalsIgnoreCase(owningOrder.getFactoryCode()))
            throw new IllegalArgumentException("PO Factory must match its Order Factory.");
        if (factoryCode != null && !factoryCode.equalsIgnoreCase(String.valueOf(po.getFactoryCode())))
            factories.requireActive(AppConstants.Buyer.LULULEMON, factoryCode);
        // Factory is optional for LULULEMON. Only enforce factory permissions when a
        // concrete factory has actually been assigned.
        assertFactoryAccessIfPresent(factoryCode);

        String poNumber;
        String style;
        int totalQty;
        int qtyPerCarton;
        List<Integer> plannedQuantities;
        int remainder;

        if (allBpPlan != null) {
            poNumber = allBpPlan.poNumber();
            style = allBpPlan.styleNumber();
            totalQty = allBpPlan.totalQty();
            qtyPerCarton = allBpPlan.qtyPerCarton();
            plannedQuantities = allBpPlan.cartonPlannedQuantities();
            remainder = allBpPlan.remainderQty();

            po.setMasterPo(allBpPlan.masterPo());
            po.setDescription(allBpPlan.description());
            po.setColor(allBpPlan.color());
            po.setSize(allBpPlan.size());
            po.setDcCode(allBpPlan.dcCode());
            po.setDestination(allBpPlan.destination());
            po.setChannel(allBpPlan.channel());
            po.setPackingPlan(allBpPlan.packingPlan());
            po.setSalesOrderPts(allBpPlan.salesOrderPts());
            po.setShipMode(allBpPlan.shipMode());
            po.setSeason(allBpPlan.season());
            po.setFwd(allBpPlan.fwd());
            po.setCartonBoxSize(allBpPlan.cartonBoxSize());
            po.setNetWeightKg(allBpPlan.netWeightKg());
            po.setGrossWeightKg(allBpPlan.grossWeightKg());
            po.setAllBpRows(allBpPlan.rows());
            if (po.getAllBpHeaders() == null || po.getAllBpHeaders().isEmpty()) {
                po.setAllBpHeaders(new ArrayList<>(allBpPlan.rows().get(0).keySet()));
            }
        } else {
            // Backward-compatible path for older clients that still submit PO-level fields only.
            poNumber = clean(request.poNumber());
            style = clean(request.styleNumber());
            totalQty = request.totalQty() == null ? 0 : request.totalQty();
            qtyPerCarton = request.qtyPerCarton() == null ? 0 : request.qtyPerCarton();
            if (poNumber == null) throw new IllegalArgumentException(AppMessages.PO_NUMBER_REQUIRED);
            if (style == null) throw new IllegalArgumentException(AppMessages.STYLE_REQUIRED);
            if (totalQty <= 0) throw new IllegalArgumentException(AppMessages.mustBeGreaterThanZero(AppConstants.FieldLabel.TOTAL_QTY));
            if (qtyPerCarton <= 0) throw new IllegalArgumentException(AppMessages.mustBeGreaterThanZero(AppConstants.FieldLabel.PCS_PER_CARTON));
            if (request.netWeightKg() != null && request.netWeightKg() < 0) throw new IllegalArgumentException(AppMessages.cannotBeNegative(AppConstants.FieldLabel.NET_WEIGHT));
            if (request.grossWeightKg() != null && request.grossWeightKg() < 0) throw new IllegalArgumentException(AppMessages.cannotBeNegative(AppConstants.FieldLabel.GROSS_WEIGHT));

            plannedQuantities = cartonQuantities(totalQty, qtyPerCarton);
            remainder = totalQty % qtyPerCarton;
            po.setMasterPo(clean(request.masterPo()));
            po.setDescription(clean(request.description()));
            po.setColor(clean(request.color()));
            po.setSize(clean(request.size()));
            po.setDcCode(clean(request.dcCode()));
            po.setDestination(clean(request.destination()));
            po.setChannel(clean(request.channel()));
            po.setPackingPlan(clean(request.packingPlan()));
            po.setSalesOrderPts(clean(request.salesOrderPts()));
            po.setShipMode(clean(request.shipMode()));
            po.setSeason(clean(request.season()));
            po.setFwd(clean(request.fwd()));
            po.setCartonBoxSize(clean(request.cartonBoxSize()));
            po.setNetWeightKg(request.netWeightKg());
            po.setGrossWeightKg(request.grossWeightKg());
        }

        // Editing must not bypass four-column identity, including case/space variants.
        AllBpPoFourKeyGuard.checkEditAgainstSaved(
                poRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(
                        orderId, AppConstants.Buyer.LULULEMON),
                po.getId(), po.getMasterPo(), poNumber, po.getPackingPlan(), po.getSalesOrderPts());

        // Claim ownership is keyed by PO Number and Factory, not row ID. Never
        // silently move a picked PO into another claim (or change its number).
        if (picked && (!Objects.equals(originalNumber, poNumber)
                || !Objects.equals(originalFactory, factoryCode))) {
            throw new IllegalArgumentException("A picked PO cannot change PO Number or Factory. Other fields remain editable until Weight completes.");
        }
        if (operational && (!Objects.equals(originalNumber, poNumber)
                || !Objects.equals(originalFactory, factoryCode)
                || !Objects.equals(originalStyle, style)
                || !Objects.equals(originalMasterPo, po.getMasterPo())
                || !Objects.equals(originalDc, po.getDcCode())
                || !Objects.equals(originalDestination, po.getDestination())
                || !Objects.equals(originalChannel, po.getChannel())
                || !Objects.equals(originalPackingPlan, po.getPackingPlan())
                || !Objects.equals(originalSalesOrderPts, po.getSalesOrderPts())
                || !Objects.equals(originalShipMode, po.getShipMode())
                || !Objects.equals(originalCartonBoxSize, po.getCartonBoxSize())
                || !Objects.equals(originalQty, totalQty)
                || !Objects.equals(originalPcs, qtyPerCarton)
                || !Objects.equals(originalPlan, plannedQuantities)
                || !Objects.equals(originalColor, po.getColor())
                || !Objects.equals(originalSize, po.getSize())
                || !Objects.equals(originalNetWeight, po.getNetWeightKg())
                || !Objects.equals(originalGrossWeight, po.getGrossWeightKg())
                || !Objects.equals(originalExFty, request.exFtyDate()))) {
            throw new IllegalArgumentException("Packing/Shipment has started: edit descriptive ALL_BP fields only. PO identity, plan, shipping keys, SKU attributes, weights, Factory and Ex-Factory date must stay unchanged to preserve cartons and shipment history.");
        }

        po.setPoNumber(poNumber);
        po.setFactoryCode(factoryCode);
        po.setStyleNumber(style);
        po.setTotalQty(totalQty);
        po.setQtyPerCarton(qtyPerCarton);
        po.setCartonCount(plannedQuantities.size());
        po.setRemainderQty(remainder);
        po.setCartonPlannedQuantities(List.copyOf(plannedQuantities));
        po.setExFtyDate(request.exFtyDate());
        if (!operational) po.setStatus(AppConstants.PurchaseOrderStatus.NOT_STARTED);
        po.setUpdatedBy(RequestActor.current());
        po.setUpdatedAt(LocalDateTime.now());

        PurchaseOrder saved = poRepository.save(po);

        if (!operational) {
            // No packing or shipment data: a full plan edit can safely regenerate children.
            itemRepository.deleteByPoId(poId);
            cartonRepository.deleteByPoId(poId);
            scanEventRepository.deleteByPoId(poId);
            if (hadGeneratedCartons) {
                List<Carton> regenerated = ensureCartonsGenerated(saved);
                if (hadGeneratedItems) regenerated.forEach(this::ensureItemsGenerated);
            }
        }

        audit(AppConstants.AuditAction.PO_UPDATE, poId,
                "Updated imported PO " + saved.getPoNumber()
                        + (operational ? ". Preserved operational Cartons/Items and shipment references."
                                : ". ALL_BP formulas recalculated; unstarted Carton/Item data refreshed."));
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.PO_UPDATED, poId);
        return saved;
    }

    /**
     * Delete an imported PO source record. Generated Cartons/Items are cascaded only
     * while the PO has no operational activity.
     */
    public synchronized void deleteImportedPo(String buyerCode, String orderId, String poId) {
        requireOrder(buyerCode, orderId);
        PurchaseOrder po = requirePo(orderId, poId);
        assertFactoryAccessIfPresent(po.getFactoryCode());
        if (po.getPlannedShippingDate() != null) {
            throw new IllegalArgumentException("PO has a planned shipping date and cannot be deleted.");
        }
        // Deletion is intentionally stricter than editing: a picked PO has a
        // claim used by Packing and may not be deleted until it is unpicked.
        assertPoWeighingNotCompleted(po);
        if (clean(po.getPickedBy()) != null) {
            throw new IllegalArgumentException("Unpick this PO before deleting it.");
        }
        assertImportedPoMutable(po);
        assertPoNotAssignedToOpenSchedule(po);

        String poNumber = po.getPoNumber();
        itemRepository.deleteByPoId(poId);
        cartonRepository.deleteByPoId(poId);
        scanEventRepository.deleteByPoId(poId);
        poRepository.delete(po);

        audit(AppConstants.AuditAction.PO_DELETE, poId, "Deleted imported PO " + poNumber + " and its unstarted generated Cartons/Items.");
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.PO_DELETED, poId);
    }

    public List<CartonItem> listItems(String buyerCode, String orderId, String cartonId) {
        requireOrder(buyerCode, orderId);
        Carton carton = requireCarton(orderId, cartonId);
        assertFactoryAccessIfPresent(carton.getFactoryCode());
        ensureItemsGenerated(carton);
        return syncItemIdentificationState(carton, itemRepository.findByCartonIdOrderByItemNoAsc(cartonId));
    }

    /** Generate Cartons only when the PO is actually opened. Safe to call repeatedly. */
    public synchronized List<Carton> ensureCartonsForPo(String buyerCode, String orderId, String poId) {
        requireOrder(buyerCode, orderId);
        PurchaseOrder po = requirePo(orderId, poId);
        assertFactoryAccessIfPresent(po.getFactoryCode());
        return ensureCartonsGenerated(po);
    }

    /** Generate Item slots only when the Carton is actually opened. Safe to call repeatedly. */
    public synchronized List<CartonItem> ensureItemsForCarton(String buyerCode, String orderId, String cartonId) {
        requireOrder(buyerCode, orderId);
        Carton carton = requireCarton(orderId, cartonId);
        assertFactoryAccessIfPresent(carton.getFactoryCode());
        ensureItemsGenerated(carton);
        return syncItemIdentificationState(carton, itemRepository.findByCartonIdOrderByItemNoAsc(cartonId));
    }

    public Carton startCarton(String buyerCode, String orderId, String cartonId) {
        requireOrder(buyerCode, orderId);
        Carton carton = requireCarton(orderId, cartonId);
        assertFactoryAccessIfPresent(carton.getFactoryCode());
        ensureItemsGenerated(carton);
        if (AppConstants.CartonStatus.FINISHED.equals(carton.getStatus()) || AppConstants.CartonStatus.LABEL_CONFIRMED.equals(carton.getStatus()) || AppConstants.CartonStatus.READY_TO_SHIP.equals(carton.getStatus())) {
            throw new IllegalArgumentException(AppMessages.cartonAlreadyFinished(carton.getCartonNo()));
        }
        if (carton.getStartedAt() == null) {
            carton.setStartedAt(LocalDateTime.now());
            carton.setStartedBy(RequestActor.current());
        }
        carton.setStatus(AppConstants.CartonStatus.PACKING);
        carton.setUpdatedAt(LocalDateTime.now());
        carton.setUpdatedBy(RequestActor.current());
        return cartonRepository.save(carton);
    }

    public synchronized ItemScanResponse scanItem(String buyerCode, String orderId, String cartonId, ItemScanRequest request) {
        requireOrder(buyerCode, orderId);
        String sku = normalizeSku(request == null ? null : request.sku());
        if (sku == null) throw new IllegalArgumentException(AppMessages.SKU_BARCODE_REQUIRED);

        Carton carton = requireCarton(orderId, cartonId);
        assertFactoryAccessIfPresent(carton.getFactoryCode());
        ensureItemsGenerated(carton);
        if (AppConstants.CartonStatus.FINISHED.equals(carton.getStatus()) || AppConstants.CartonStatus.LABEL_CONFIRMED.equals(carton.getStatus()) || AppConstants.CartonStatus.READY_TO_SHIP.equals(carton.getStatus())) {
            rejectScan(orderId, carton.getPoId(), cartonId, AppConstants.ScanType.ITEM, sku, AppMessages.CARTON_ALREADY_FINISHED_SCAN_REASON);
            throw new IllegalArgumentException(AppMessages.CARTON_FINISHED_SCAN_BLOCKED);
        }

        PurchaseOrder po = requirePo(orderId, carton.getPoId());
        boolean assignedNow = false;
        if (clean(po.getSku()) == null) {
            assertSkuAvailableForPo(po, sku);
            Query query = Query.query(Criteria.where("_id").is(po.getId()).andOperator(
                    new Criteria().orOperator(Criteria.where("sku").is(null), Criteria.where("sku").is(""))));
            Update update = new Update()
                    .set("sku", sku)
                    .set("skuAssignedBy", RequestActor.current())
                    .set("skuAssignedAt", LocalDateTime.now())
                    .set("updatedBy", RequestActor.current())
                    .set("updatedAt", LocalDateTime.now());
            PurchaseOrder claimed = mongoTemplate.findAndModify(query, update,
                    FindAndModifyOptions.options().returnNew(true), PurchaseOrder.class);
            if (claimed != null) {
                po = claimed;
                assignedNow = true;
            } else {
                po = requirePo(orderId, po.getId());
            }
        }

        if (!sku.equals(normalizeSku(po.getSku()))) {
            rejectScan(orderId, po.getId(), cartonId, AppConstants.ScanType.ITEM, sku,
                    AppMessages.wrongSkuLocked(po.getSku()));
            throw new IllegalArgumentException(AppMessages.wrongSku(po.getPoNumber(), po.getSku()));
        }

        Query nextItem = Query.query(Criteria.where("cartonId").is(cartonId)
                .and("status").in(AppConstants.ItemStatus.PENDING, AppConstants.ItemStatus.IDENTIFIED))
                .with(Sort.by(Sort.Direction.ASC, "itemNo"));
        Update scanUpdate = new Update()
                .set("status", AppConstants.ItemStatus.PASS)
                .set("scannedSku", sku)
                .set("scannedBy", RequestActor.current())
                .set("scannedAt", LocalDateTime.now());
        CartonItem item = mongoTemplate.findAndModify(nextItem, scanUpdate,
                FindAndModifyOptions.options().returnNew(true), CartonItem.class);
        if (item == null) {
            rejectScan(orderId, po.getId(), cartonId, AppConstants.ScanType.ITEM, sku, AppMessages.CARTON_PLANNED_QUANTITY_REACHED_SCAN_REASON);
            throw new IllegalArgumentException(AppMessages.cartonScanLimit(carton.getPlannedQty()));
        }

        int scannedQty = Math.toIntExact(itemRepository.countByCartonIdAndStatus(cartonId, AppConstants.ItemStatus.PASS));
        carton.setScannedQty(scannedQty);
        boolean autoFinished = scannedQty == safeInt(carton.getPlannedQty());
        LocalDateTime scanTime = LocalDateTime.now();
        boolean identityComplete = carton.getLabelConfirmedAt() != null && clean(carton.getSscc()) != null;
        carton.setStatus(autoFinished
                ? (identityComplete ? AppConstants.CartonStatus.READY_TO_SHIP : AppConstants.CartonStatus.FINISHED)
                : AppConstants.CartonStatus.PACKING);
        if (carton.getStartedAt() == null) {
            carton.setStartedAt(scanTime);
            carton.setStartedBy(RequestActor.current());
        }
        if (autoFinished) {
            carton.setFinishedBy(RequestActor.current());
            carton.setFinishedAt(scanTime);
        }
        carton.setUpdatedBy(RequestActor.current());
        carton.setUpdatedAt(scanTime);
        carton = cartonRepository.save(carton);

        if (autoFinished) {
            refreshPoStatus(po);
            po = requirePo(orderId, po.getId());
            audit(AppConstants.AuditAction.FINISH_CARTON, cartonId,
                    AppMessages.finishCartonAudit(po.getPoNumber(), carton.getCartonNo(), scannedQty) + " (auto-finished by final accepted scan)");
            socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.CARTON_FINISHED, cartonId);
        } else if (!AppConstants.PurchaseOrderStatus.PACKING.equals(po.getStatus())) {
            po.setStatus(AppConstants.PurchaseOrderStatus.PACKING);
            po.setUpdatedAt(scanTime);
            po.setUpdatedBy(RequestActor.current());
            po = poRepository.save(po);
        }

        passScan(orderId, po.getId(), cartonId, AppConstants.ScanType.ITEM, sku,
                AppMessages.acceptedItem(item.getItemNo(), carton.getPlannedQty()));
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.ITEM_SCANNED, cartonId);
        String responseMessage = autoFinished
                ? AppMessages.cartonAutoFinished(carton.getCartonNo(), scannedQty)
                : (assignedNow ? AppMessages.FIRST_SKU_ASSIGNED : AppMessages.SKU_ACCEPTED);
        return new ItemScanResponse(po, carton, item, assignedNow, responseMessage);
    }

    public synchronized Carton undoLastItem(String buyerCode, String orderId, String cartonId) {
        requireOrder(buyerCode, orderId);
        Carton carton = requireCarton(orderId, cartonId);
        assertFactoryAccessIfPresent(carton.getFactoryCode());
        if (AppConstants.CartonStatus.FINISHED.equals(carton.getStatus()) || AppConstants.CartonStatus.LABEL_CONFIRMED.equals(carton.getStatus()) || AppConstants.CartonStatus.READY_TO_SHIP.equals(carton.getStatus())) {
            throw new IllegalArgumentException(AppMessages.FINISHED_CARTON_UNDO_BLOCKED);
        }
        CartonItem item = itemRepository.findFirstByCartonIdAndStatusOrderByItemNoDesc(cartonId, AppConstants.ItemStatus.PASS)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.NO_ACCEPTED_SCAN_TO_UNDO));
        boolean identified = normalizeSku(item.getExpectedSku()) != null;
        item.setStatus(identified ? AppConstants.ItemStatus.IDENTIFIED : AppConstants.ItemStatus.PENDING);
        if (identified && clean(item.getRfidStatus()) == null) item.setRfidStatus(AppConstants.RfidStatus.WAITING_RFID);
        item.setScannedSku(null);
        item.setScannedBy(null);
        item.setScannedAt(null);
        itemRepository.save(item);

        int scannedQty = Math.toIntExact(itemRepository.countByCartonIdAndStatus(cartonId, AppConstants.ItemStatus.PASS));
        carton.setScannedQty(scannedQty);
        carton.setStatus(scannedQty == 0 ? AppConstants.CartonStatus.READY_TO_PACK : AppConstants.CartonStatus.PACKING);
        carton.setUpdatedAt(LocalDateTime.now());
        carton.setUpdatedBy(RequestActor.current());
        cartonRepository.save(carton);

        PurchaseOrder po = requirePo(orderId, carton.getPoId());
        long poScans = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId()).stream()
                .mapToLong(value -> value.getScannedQty() == null ? 0 : value.getScannedQty())
                .sum();
        if (poScans == 0 && cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId()).stream().noneMatch(c -> c.getSscc() != null)) {
            po.setSku(null);
            po.setSkuAssignedAt(null);
            po.setSkuAssignedBy(null);
            po.setStatus(AppConstants.PurchaseOrderStatus.NOT_STARTED);
            po.setUpdatedAt(LocalDateTime.now());
            po.setUpdatedBy(RequestActor.current());
            poRepository.save(po);
        }
        audit(AppConstants.AuditAction.UNDO_ITEM, cartonId, AppMessages.undoLastItemAudit(carton.getCartonNo()));
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.ITEM_UNDONE, cartonId);
        return carton;
    }

    public synchronized Carton finishCarton(String buyerCode, String orderId, String cartonId) {
        requireOrder(buyerCode, orderId);
        Carton carton = requireCarton(orderId, cartonId);
        assertFactoryAccessIfPresent(carton.getFactoryCode());
        if (AppConstants.CartonStatus.READY_TO_SHIP.equals(carton.getStatus())) return carton;
        int scanned = Math.toIntExact(itemRepository.countByCartonIdAndStatus(cartonId, AppConstants.ItemStatus.PASS));
        if (scanned != safeInt(carton.getPlannedQty())) {
            throw new IllegalArgumentException(AppMessages.cartonFinishQuantity(scanned, carton.getPlannedQty()));
        }
        PurchaseOrder po = requirePo(orderId, carton.getPoId());
        if (normalizeSku(po.getSku()) == null) throw new IllegalArgumentException(AppMessages.PO_SKU_NOT_ASSIGNED);
        carton.setScannedQty(scanned);
        boolean identityComplete = carton.getLabelConfirmedAt() != null && clean(carton.getSscc()) != null;
        carton.setStatus(identityComplete ? AppConstants.CartonStatus.READY_TO_SHIP : AppConstants.CartonStatus.FINISHED);
        carton.setFinishedBy(RequestActor.current());
        carton.setFinishedAt(LocalDateTime.now());
        carton.setUpdatedBy(RequestActor.current());
        carton.setUpdatedAt(LocalDateTime.now());
        carton = cartonRepository.save(carton);
        refreshPoStatus(po);
        audit(AppConstants.AuditAction.FINISH_CARTON, cartonId,
                AppMessages.finishCartonAudit(po.getPoNumber(), carton.getCartonNo(), scanned));
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.CARTON_FINISHED, cartonId);
        return carton;
    }

    public ImportResult importShippingList(String buyerCode, String orderId, MultipartFile file) {
        requireOrder(buyerCode, orderId);
        int updated = 0;
        List<String> warnings = new ArrayList<>();
        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = findShippingSheet(workbook);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            int headerRowIndex = findShippingHeaderRow(sheet, evaluator);
            Row header = sheet.getRow(headerRowIndex);
            Map<String, Integer> columns = headerColumns(header, evaluator);
            int poCol = requiredColumn(columns, AppConstants.AllBpKey.PO, "PONO", "PURCHASEORDER");
            int exFtyCol = requiredColumn(columns, "EXFTYDATE", "EXFACTORYDATE", "EXFTY", "EXFACTORY");

            for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                String poNumber = clean(excelSupport.text(row, poCol, evaluator));
                if (poNumber == null) continue;
                LocalDate exFty = excelSupport.localDate(row, exFtyCol, evaluator);
                if (exFty == null) {
                    warnings.add(AppMessages.shippingPoMissingExFty(poNumber, rowIndex + 1));
                    continue;
                }
                List<PurchaseOrder> matchingPos = poRepository.findAllByOrderIdAndBuyerCodeAndPoNumber(orderId, AppConstants.Buyer.LULULEMON, poNumber);
                if (matchingPos.isEmpty()) {
                    warnings.add(AppMessages.shippingPoNotInAllBp(poNumber));
                    continue;
                }
                for (PurchaseOrder po : matchingPos) {
                    po.setExFtyDate(exFty);
                    po.setUpdatedAt(LocalDateTime.now());
                    po.setUpdatedBy(RequestActor.current());
                    refreshPoStatus(po);
                    updated++;
                }
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException(AppMessages.cannotImportForBuyer(AppConstants.Buyer.LULULEMON, AppConstants.ImportSource.SHIPPING_LIST, cleanMessage(ex)));
        }
        audit(AppConstants.AuditAction.SHIPPING_IMPORT, orderId, "Updated Ex-fty Date for " + updated + " PO(s)");
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.EX_FTY_UPDATED, orderId);
        return new ImportResult(true, 0, 0, 0, updated, warnings);
    }

    public PurchaseOrder updateExFtyDate(String buyerCode, String orderId, ShippingUpdateRequest request) {
        requireOrder(buyerCode, orderId);
        if (request == null || clean(request.poNumber()) == null || request.exFtyDate() == null) {
            throw new IllegalArgumentException(AppMessages.PO_AND_EX_FTY_REQUIRED);
        }
        List<PurchaseOrder> matchingPos = poRepository.findAllByOrderIdAndBuyerCodeAndPoNumber(
                orderId, AppConstants.Buyer.LULULEMON, clean(request.poNumber()));
        if (matchingPos.isEmpty()) {
            throw new IllegalArgumentException(AppMessages.poNotInAllBp(request.poNumber()));
        }
        for (PurchaseOrder po : matchingPos) {
            po.setExFtyDate(request.exFtyDate());
            po.setUpdatedAt(LocalDateTime.now());
            po.setUpdatedBy(RequestActor.current());
            refreshPoStatus(po);
        }
        return matchingPos.get(0);
    }

    public LabelLookupResponse lookupCartonLabel(String buyerCode, String orderId, LabelLookupRequest request) {
        requireOrder(buyerCode, orderId);
        String poNumber = clean(request == null ? null : request.poNumber());
        String sku = normalizeSku(request == null ? null : request.sku());
        if (poNumber == null) throw new IllegalArgumentException(AppMessages.CARTON_LABEL_PO_REQUIRED);
        if (sku == null) throw new IllegalArgumentException(AppMessages.CARTON_LABEL_SKU_REQUIRED);

        List<LabelCandidate> flatCandidates = new ArrayList<>();
        for (PurchaseOrder po : poRepository.findAllByOrderIdAndBuyerCodeAndPoNumber(
                orderId, AppConstants.Buyer.LULULEMON, poNumber)) {
            if (!canAccessFactoryOrUnassigned(po.getFactoryCode())) continue;

            String assignedSku = normalizeSku(po.getSku());
            // Imported POs intentionally start without SKU. Once a SKU has been
            // identified for the logical PO, later carton labels must use the same SKU.
            if (assignedSku != null && !assignedSku.equals(sku)) continue;

            List<Carton> pending = ensureCartonsGenerated(po).stream()
                    .filter(c -> clean(c.getSscc()) == null)
                    .filter(c -> AppConstants.CartonStatus.READY_TO_PACK.equals(c.getStatus())
                            || AppConstants.CartonStatus.FINISHED.equals(c.getStatus())
                            || AppConstants.CartonStatus.LABEL_CONFIRMED.equals(c.getStatus()))
                    .sorted(Comparator.comparing(c -> c.getCartonNo() == null ? Integer.MAX_VALUE : c.getCartonNo()))
                    .toList();
            pending.forEach(carton -> flatCandidates.add(new LabelCandidate(po, carton)));
        }

        if (flatCandidates.isEmpty()) {
            rejectScan(orderId, null, null, AppConstants.ScanType.CARTON_LABEL_SKU, sku,
                    "No carton is waiting for identification for PO " + poNumber);
            throw new IllegalArgumentException("No carton for PO " + poNumber + " / SKU " + sku
                    + " is waiting for carton identification or SSCC assignment.");
        }

        LabelCandidate only = flatCandidates.size() == 1 ? flatCandidates.get(0) : null;
        passScan(orderId,
                only == null ? null : only.po().getId(),
                only == null ? null : only.carton().getId(),
                AppConstants.ScanType.CARTON_LABEL_SKU,
                sku,
                "Located " + flatCandidates.size() + " carton candidate(s) for PO " + poNumber);

        PurchaseOrder responsePo = only == null ? null : only.po();
        List<Carton> responseCartons = responsePo == null
                ? List.of()
                : flatCandidates.stream().filter(c -> Objects.equals(c.po().getId(), responsePo.getId())).map(LabelCandidate::carton).toList();
        return new LabelLookupResponse(
                sku,
                List.copyOf(flatCandidates),
                responsePo,
                responseCartons,
                AppMessages.CARTON_LABEL_FIFO_INSTRUCTION
        );
    }

    public synchronized Carton confirmCartonQuantity(String buyerCode, String orderId, String cartonId) {
        requireOrder(buyerCode, orderId);
        Carton carton = requireCarton(orderId, cartonId);
        assertFactoryAccessIfPresent(carton.getFactoryCode());

        String currentStatus = clean(carton.getStatus());
        boolean unstarted = AppConstants.CartonStatus.READY_TO_PACK.equals(currentStatus);
        boolean packingComplete = AppConstants.CartonStatus.FINISHED.equals(currentStatus)
                || AppConstants.CartonStatus.LABEL_CONFIRMED.equals(currentStatus)
                || AppConstants.CartonStatus.READY_TO_SHIP.equals(currentStatus)
                || carton.getFinishedAt() != null;
        if (!unstarted && !packingComplete) {
            throw new IllegalArgumentException(AppMessages.CARTON_IDENTITY_STATUS_INVALID);
        }

        if (clean(carton.getSscc()) == null) {
            throw new IllegalArgumentException(AppMessages.SSCC_REQUIRED_BEFORE_QUANTITY_CONFIRMATION);
        }

        int expectedQty = safeInt(carton.getPlannedQty());
        if (expectedQty <= 0) throw new IllegalArgumentException(AppMessages.CARTON_QUANTITY_INCOMPLETE);

        if (carton.getQtyConfirmedAt() == null) {
            LocalDateTime now = LocalDateTime.now();
            carton.setQtyConfirmedBy(RequestActor.current());
            carton.setQtyConfirmedAt(now);
            carton.setUpdatedBy(RequestActor.current());
            carton.setUpdatedAt(now);
            carton = cartonRepository.save(carton);

            audit(AppConstants.AuditAction.CONFIRM_CARTON_QTY, cartonId,
                    "Confirmed system Unit Qty " + expectedQty + " for PO " + carton.getPoNumber()
                            + " carton " + carton.getCartonNo() + " after SSCC assignment.");
            socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.CARTON_QTY_CONFIRMED, cartonId);
        }
        return carton;
    }

    public synchronized Carton confirmCartonLabel(String buyerCode, String orderId, String cartonId, ConfirmLabelRequest request) {
        requireOrder(buyerCode, orderId);
        Carton carton = requireCarton(orderId, cartonId);
        assertFactoryAccessIfPresent(carton.getFactoryCode());

        String currentStatus = clean(carton.getStatus());
        boolean unstarted = AppConstants.CartonStatus.READY_TO_PACK.equals(currentStatus);
        boolean packingComplete = AppConstants.CartonStatus.FINISHED.equals(currentStatus)
                || AppConstants.CartonStatus.LABEL_CONFIRMED.equals(currentStatus)
                || AppConstants.CartonStatus.READY_TO_SHIP.equals(currentStatus)
                || carton.getFinishedAt() != null;
        if (!unstarted && !packingComplete) {
            throw new IllegalArgumentException(AppMessages.CARTON_IDENTITY_STATUS_INVALID);
        }

        if (clean(carton.getSscc()) == null) {
            throw new IllegalArgumentException(AppMessages.SSCC_REQUIRED_BEFORE_QUANTITY_CONFIRMATION);
        }
        // Backward compatibility: cartons fully identified by an earlier build did not
        // persist a separate quantity-confirmation timestamp. Their label confirmation
        // already proves that the system Unit Qty was accepted in the old sequence.
        boolean quantityConfirmed = carton.getQtyConfirmedAt() != null || carton.getLabelConfirmedAt() != null;
        if (!quantityConfirmed) {
            throw new IllegalArgumentException(AppMessages.QUANTITY_CONFIRMATION_REQUIRED_BEFORE_SKU);
        }

        PurchaseOrder po = requirePo(orderId, carton.getPoId());
        String scannedSku = normalizeSku(request == null ? null : request.sku());
        if (scannedSku == null) throw new IllegalArgumentException(AppMessages.CARTON_LABEL_SKU_REQUIRED);

        String assignedSku = normalizeSku(po.getSku());
        if (assignedSku != null && !assignedSku.equals(scannedSku)) {
            rejectScan(orderId, po.getId(), cartonId, AppConstants.ScanType.CARTON_LABEL_SKU, scannedSku,
                    AppMessages.CARTON_LABEL_SKU_MISMATCH_SCAN_REASON);
            throw new IllegalArgumentException(AppMessages.cartonLabelSkuMismatch(po.getPoNumber()));
        }

        int expectedQty = safeInt(carton.getPlannedQty());
        if (expectedQty <= 0) throw new IllegalArgumentException(AppMessages.CARTON_QUANTITY_INCOMPLETE);

        // New identification sequence: SSCC is assigned first, then the user confirms
        // the system planned quantity, and SKU is scanned last. The SKU scan is allowed
        // to establish the PO SKU; no quantity is accepted from the client.
        if (assignedSku == null) {
            assertSkuAvailableForPo(po, scannedSku);
            Query claimSku = Query.query(Criteria.where("_id").is(po.getId()).andOperator(
                    new Criteria().orOperator(Criteria.where("sku").is(null), Criteria.where("sku").is(""))));
            Update claimSkuUpdate = new Update()
                    .set("sku", scannedSku)
                    .set("skuAssignedBy", RequestActor.current())
                    .set("skuAssignedAt", LocalDateTime.now())
                    .set("updatedBy", RequestActor.current())
                    .set("updatedAt", LocalDateTime.now());
            PurchaseOrder claimed = mongoTemplate.findAndModify(claimSku, claimSkuUpdate,
                    FindAndModifyOptions.options().returnNew(true), PurchaseOrder.class);
            po = claimed == null ? requirePo(orderId, po.getId()) : claimed;
            if (!scannedSku.equals(normalizeSku(po.getSku()))) {
                throw new IllegalArgumentException(AppMessages.cartonLabelSkuMismatch(po.getPoNumber()));
            }
        }

        // Materialize the logical item slots at carton-identification time and apply
        // the carton-label SKU to every slot. Identification is complete at this point,
        // so the logical item status becomes IDENTIFIED. RFID verification remains a
        // separate status and scannedQty is not increased.
        ensureItemsGenerated(carton);
        List<CartonItem> itemSlots = itemRepository.findByCartonIdOrderByItemNoAsc(cartonId);
        if (itemSlots.size() != expectedQty) {
            throw new IllegalArgumentException(AppMessages.CARTON_QUANTITY_INCOMPLETE);
        }
        LocalDateTime identityTime = LocalDateTime.now();
        for (CartonItem item : itemSlots) {
            item.setExpectedSku(scannedSku);
            if (!AppConstants.ItemStatus.PASS.equals(item.getStatus())) {
                item.setStatus(AppConstants.ItemStatus.IDENTIFIED);
            }
            item.setRfidStatus(AppConstants.RfidStatus.WAITING_RFID);
            item.setUpdatedAt(identityTime);
        }
        if (!itemSlots.isEmpty()) itemRepository.saveAll(itemSlots);

        carton.setLabelConfirmedSku(scannedSku);
        carton.setLabelConfirmedBy(RequestActor.current());
        carton.setLabelConfirmedAt(identityTime);
        // Preserve READY_TO_PACK for cartons that are only identified. They are not
        // READY_TO_SHIP until product verification (barcode/RFID) has completed.
        if (packingComplete && !AppConstants.CartonStatus.READY_TO_SHIP.equals(currentStatus)) {
            carton.setStatus(clean(carton.getSscc()) != null
                    ? AppConstants.CartonStatus.READY_TO_SHIP
                    : AppConstants.CartonStatus.LABEL_CONFIRMED);
        }
        carton.setUpdatedBy(RequestActor.current());
        carton.setUpdatedAt(LocalDateTime.now());
        carton = cartonRepository.save(carton);

        if (packingComplete) refreshPoStatus(po);
        audit(AppConstants.AuditAction.CONFIRM_CARTON_LABEL, cartonId,
                "Confirmed carton identity for PO " + po.getPoNumber() + " carton " + carton.getCartonNo()
                        + ", SKU " + scannedSku + ", system Unit Qty " + expectedQty
                        + " after SSCC " + carton.getSscc() + ". Applied expected SKU to " + itemSlots.size()
                        + " logical item slot(s); item status is IDENTIFIED and RFID status is WAITING_RFID.");
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.LABEL_CONFIRMED, cartonId);
        return carton;
    }

    public synchronized Carton assignSscc(String buyerCode, String orderId, String cartonId, AssignSsccRequest request) {
        requireOrder(buyerCode, orderId);
        Carton carton = requireCarton(orderId, cartonId);
        assertFactoryAccessIfPresent(carton.getFactoryCode());
        String sscc = normalizeSscc(request == null ? null : request.sscc());
        if (sscc == null) throw new IllegalArgumentException(AppMessages.SSCC_REQUIRED);
        String currentStatus = clean(carton.getStatus());
        boolean unstarted = AppConstants.CartonStatus.READY_TO_PACK.equals(currentStatus);
        boolean packingComplete = AppConstants.CartonStatus.FINISHED.equals(currentStatus)
                || AppConstants.CartonStatus.LABEL_CONFIRMED.equals(currentStatus)
                || AppConstants.CartonStatus.READY_TO_SHIP.equals(currentStatus)
                || carton.getFinishedAt() != null;
        if (!unstarted && !packingComplete) {
            throw new IllegalArgumentException(AppMessages.CARTON_IDENTITY_STATUS_INVALID);
        }

        if (clean(carton.getSscc()) != null) {
            throw new IllegalArgumentException(AppMessages.cartonAlreadyHasSscc(carton.getSscc()));
        }
        if (cartonRepository.existsBySscc18(sscc)) {
            rejectScan(orderId, carton.getPoId(), cartonId, AppConstants.ScanType.SSCC, sscc, AppMessages.SSCC_ALREADY_ASSIGNED_SCAN_REASON);
            throw new IllegalArgumentException(AppMessages.ssccAlreadyAssigned(sscc));
        }

        carton.setSscc(sscc);
        carton.setSsccAssignedBy(RequestActor.current());
        carton.setSsccAssignedAt(LocalDateTime.now());
        // SSCC is now the first identification step. It never completes identity by itself.
        // A legacy finished carton becomes READY_TO_SHIP only when SKU/label identity was
        // already confirmed; otherwise it waits for Qty confirmation and the final SKU scan.
        boolean skuIdentityConfirmed = carton.getLabelConfirmedAt() != null
                && normalizeSku(carton.getLabelConfirmedSku()) != null;
        if (packingComplete && skuIdentityConfirmed) carton.setStatus(AppConstants.CartonStatus.READY_TO_SHIP);
        carton.setUpdatedBy(RequestActor.current());
        carton.setUpdatedAt(LocalDateTime.now());
        carton = cartonRepository.save(carton);

        PurchaseOrder po = requirePo(orderId, carton.getPoId());
        if (packingComplete && skuIdentityConfirmed) refreshPoStatus(po);
        passScan(orderId, po.getId(), cartonId, AppConstants.ScanType.SSCC, sscc, AppMessages.SSCC_ASSIGNED_SUCCESSFULLY);
        audit(AppConstants.AuditAction.ASSIGN_SSCC, cartonId,
                AppMessages.assignSsccAudit(sscc, po.getPoNumber(), carton.getCartonNo())
                        + " SSCC saved as step 1 of carton identification; confirm system Unit Qty next.");
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.SSCC_ASSIGNED, cartonId);
        return carton;
    }

    public synchronized PurchaseOrder resetPoSku(String buyerCode, String orderId, String poId, ExceptionRequest request) {
        requireOrder(buyerCode, orderId);
        String reason = requireReason(request);
        PurchaseOrder po = requirePo(orderId, poId);
        assertFactoryAccessIfPresent(po.getFactoryCode());

        List<Carton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(poId);
        boolean finalized = cartons.stream().anyMatch(c ->
                AppConstants.CartonStatus.FINISHED.equals(c.getStatus())
                        || AppConstants.CartonStatus.LABEL_CONFIRMED.equals(c.getStatus())
                        || AppConstants.CartonStatus.READY_TO_SHIP.equals(c.getStatus())
                        || clean(c.getSscc()) != null);
        if (finalized) {
            throw new IllegalArgumentException(AppMessages.RESET_SKU_REQUIRES_REOPEN);
        }

        String oldSku = po.getSku();
        List<CartonItem> items = itemRepository.findByPoIdOrderByCartonNoAscItemNoAsc(poId);
        for (CartonItem item : items) {
            if (AppConstants.ItemStatus.PASS.equals(item.getStatus())
                    || clean(item.getExpectedSku()) != null
                    || clean(item.getScannedSku()) != null) {
                item.setStatus(AppConstants.ItemStatus.PENDING);
                item.setExpectedSku(null);
                item.setRfidStatus(null);
                item.setScannedSku(null);
                item.setScannedBy(null);
                item.setScannedAt(null);
                item.setUpdatedAt(LocalDateTime.now());
            }
        }
        if (!items.isEmpty()) itemRepository.saveAll(items);

        for (Carton carton : cartons) {
            carton.setScannedQty(0);
            carton.setStatus(AppConstants.CartonStatus.READY_TO_PACK);
            carton.setStartedAt(null);
            carton.setStartedBy(null);
            // Carton identification establishes the PO/item expected SKU. Resetting
            // the PO SKU before SSCC assignment must also clear that identification.
            carton.setLabelConfirmedAt(null);
            carton.setLabelConfirmedBy(null);
            carton.setLabelConfirmedSku(null);
            carton.setUpdatedAt(LocalDateTime.now());
            carton.setUpdatedBy(RequestActor.current());
        }
        if (!cartons.isEmpty()) cartonRepository.saveAll(cartons);

        po.setSku(null);
        po.setSkuAssignedAt(null);
        po.setSkuAssignedBy(null);
        po.setStatus(AppConstants.PurchaseOrderStatus.NOT_STARTED);
        po.setUpdatedAt(LocalDateTime.now());
        po.setUpdatedBy(RequestActor.current());
        po = poRepository.save(po);

        audit(AppConstants.AuditAction.RESET_PO_SKU, poId,
                AppMessages.resetPoSkuAudit(safe(oldSku), po.getPoNumber(), reason));
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.PO_SKU_RESET, poId);
        return po;
    }

    public synchronized Carton reopenCarton(String buyerCode, String orderId, String cartonId, ExceptionRequest request) {
        requireOrder(buyerCode, orderId);
        String reason = requireReason(request);
        Carton carton = requireCarton(orderId, cartonId);
        assertFactoryAccessIfPresent(carton.getFactoryCode());
        if (clean(carton.getSscc()) != null) {
            throw new IllegalArgumentException(AppMessages.UNASSIGN_SSCC_BEFORE_REOPEN);
        }
        if (clean(carton.getWeightStatus()) != null) {
            throw new IllegalArgumentException(AppMessages.WEIGHING_RESULT_BLOCKS_CARTON_REOPEN);
        }
        if (!AppConstants.CartonStatus.FINISHED.equals(carton.getStatus()) && !AppConstants.CartonStatus.LABEL_CONFIRMED.equals(carton.getStatus())) {
            throw new IllegalArgumentException(AppMessages.CARTON_REOPEN_STATUS_INVALID);
        }

        String oldStatus = carton.getStatus();
        carton.setStatus(safeInt(carton.getScannedQty()) > 0 ? AppConstants.CartonStatus.PACKING : AppConstants.CartonStatus.READY_TO_PACK);
        carton.setFinishedAt(null);
        carton.setFinishedBy(null);
        carton.setLabelConfirmedAt(null);
        carton.setLabelConfirmedBy(null);
        carton.setLabelConfirmedSku(null);
        carton.setUpdatedAt(LocalDateTime.now());
        carton.setUpdatedBy(RequestActor.current());
        carton = cartonRepository.save(carton);

        PurchaseOrder po = requirePo(orderId, carton.getPoId());
        refreshPoStatus(po);
        audit(AppConstants.AuditAction.REOPEN_CARTON, cartonId,
                AppMessages.reopenCartonAudit(po.getPoNumber(), carton.getCartonNo(), oldStatus, reason));
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.CARTON_REOPENED, cartonId);
        return carton;
    }

    /**
     * Operator correction immediately after Assign / Identify (BEFORE starting RFID).
     * Undo SSCC and carton-label SKU together; keep PO master SKU, logical Item IDs
     * and all audit history. Legacy Admin SSCC-unassign remains a separate action.
     */
    public synchronized Carton unassignNewCartonIdentity(String buyerCode, String orderId,
                                                          String cartonId, ExceptionRequest request) {
        requireOrder(buyerCode, orderId);
        Carton carton = requireCarton(orderId, cartonId);
        assertFactoryAccessIfPresent(carton.getFactoryCode());
        String reason = requireReason(request);
        String previousSscc = clean(carton.getSscc());
        String previousSku = clean(carton.getLabelConfirmedSku());
        if (previousSscc == null || carton.getLabelConfirmedAt() == null) {
            throw new IllegalArgumentException("Carton SSCC + SKU have not been identified together.");
        }
        // An operator can correct their own recent identification. Admin may
        // correct a colleague's identification, still subject to the history guards.
        if (!factoryAccess.currentUser().isAdminRole()
                && !Objects.equals(clean(carton.getSsccAssignedBy()), clean(RequestActor.current()))) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only the user who assigned this SSCC or Admin can unassign it.");
        }
        // Do not allow this operator-level correction to destroy a real packed,
        // inspected or weighed carton. The RFID session table is authoritative,
        // including SCANNING/FAILED attempts and historical retries.
        if (carton.getFinishedAt() != null || safeInt(carton.getScannedQty()) > 0
                || clean(carton.getRfidSessionId()) != null || clean(carton.getRfidStatus()) != null
                || clean(carton.getWeightStatus()) != null || carton.getWeighedAt() != null
                || AppConstants.CartonStatus.FINISHED.equals(carton.getStatus())
                || AppConstants.CartonStatus.READY_TO_SHIP.equals(carton.getStatus())
                || AppConstants.CartonStatus.LABEL_CONFIRMED.equals(carton.getStatus())) {
            throw new IllegalArgumentException("Carton already has packing/RFID/Weight activity. Use Admin review; unassign is only allowed before RFID starts.");
        }
        Query rfidHistory = Query.query(Criteria.where("cartonId").is(cartonId)
                .and("buyerCode").is(AppConstants.Buyer.LULULEMON));
        if (mongoTemplate.exists(rfidHistory,
                org.bsl.cartonloading.buyers.lululemon.model.RfidScanSession.class)) {
            throw new IllegalArgumentException("An RFID session already exists for this carton. Unassign is blocked to preserve its inspection history.");
        }
        Query weightHistory = Query.query(Criteria.where("cartonId").is(cartonId)
                .and("buyerCode").is(AppConstants.Buyer.LULULEMON));
        if (mongoTemplate.exists(weightHistory, WeighingResult.class)) {
            throw new IllegalArgumentException("Weight history exists for this carton. Unassign is blocked.");
        }
        List<CartonItem> itemSlots = itemRepository.findByCartonIdOrderByItemNoAsc(cartonId);
        for (CartonItem item : itemSlots) {
            if (AppConstants.ItemStatus.PASS.equals(item.getStatus())
                    || clean(item.getScannedSku()) != null || clean(item.getRfidEpc()) != null
                    || item.getRfidVerifiedAt() != null
                    || (clean(item.getRfidStatus()) != null
                        && !AppConstants.RfidStatus.WAITING_RFID.equals(item.getRfidStatus()))) {
                throw new IllegalArgumentException("An Item already has scan/RFID results. Use Admin review instead.");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        for (CartonItem item : itemSlots) {
            item.setExpectedSku(null);
            item.setStatus(AppConstants.ItemStatus.PENDING);
            item.setRfidStatus(null);
            item.setUpdatedAt(now);
        }
        if (!itemSlots.isEmpty()) itemRepository.saveAll(itemSlots);
        carton.setSscc(null);
        carton.setSsccAssignedAt(null);
        carton.setSsccAssignedBy(null);
        carton.setLabelConfirmedSku(null);
        carton.setLabelConfirmedAt(null);
        carton.setLabelConfirmedBy(null);
        carton.setQtyConfirmedAt(null);
        carton.setQtyConfirmedBy(null);
        carton.setStatus(AppConstants.CartonStatus.READY_TO_PACK);
        carton.setUpdatedAt(now);
        carton.setUpdatedBy(RequestActor.current());
        carton = cartonRepository.save(carton);
        // The master PO SKU from ALL_BP remains unchanged. No Items are deleted.
        audit(AppConstants.AuditAction.UNASSIGN_CARTON_IDENTITY, cartonId,
                "Operator unassigned newly identified carton " + carton.getCartonNo()
                    + " (PO " + carton.getPoNumber() + ", Factory " + carton.getFactoryCode()
                    + "): SSCC " + previousSscc + ", SKU " + previousSku
                    + ". Reason: " + reason + ". Preserved " + itemSlots.size() + " Item IDs and PO SKU.");
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.SSCC_UNASSIGNED, cartonId);
        return carton;
    }

    public synchronized Carton unassignSscc(String buyerCode, String orderId, String cartonId, ExceptionRequest request) {
        requireOrder(buyerCode, orderId);
        String reason = requireReason(request);
        Carton carton = requireCarton(orderId, cartonId);
        assertFactoryAccessIfPresent(carton.getFactoryCode());
        String oldSscc = clean(carton.getSscc());
        if (oldSscc == null) throw new IllegalArgumentException(AppMessages.CARTON_HAS_NO_SSCC);
        if (clean(carton.getWeightStatus()) != null) {
            throw new IllegalArgumentException(AppMessages.WEIGHING_RESULT_BLOCKS_SSCC_UNASSIGN);
        }

        boolean packingComplete = carton.getFinishedAt() != null
                || AppConstants.CartonStatus.FINISHED.equals(carton.getStatus())
                || AppConstants.CartonStatus.LABEL_CONFIRMED.equals(carton.getStatus())
                || AppConstants.CartonStatus.READY_TO_SHIP.equals(carton.getStatus());

        carton.setSscc(null);
        carton.setSsccAssignedAt(null);
        carton.setSsccAssignedBy(null);
        // Pre-RFID identification may have an SSCC while the carton is still
        // READY_TO_PACK. Removing that SSCC must not make it look packed.
        carton.setStatus(packingComplete
                ? (carton.getLabelConfirmedAt() == null ? AppConstants.CartonStatus.FINISHED : AppConstants.CartonStatus.LABEL_CONFIRMED)
                : AppConstants.CartonStatus.READY_TO_PACK);
        carton.setUpdatedAt(LocalDateTime.now());
        carton.setUpdatedBy(RequestActor.current());
        carton = cartonRepository.save(carton);

        PurchaseOrder po = requirePo(orderId, carton.getPoId());
        refreshPoStatus(po);
        audit(AppConstants.AuditAction.UNASSIGN_SSCC, cartonId,
                AppMessages.unassignSsccAudit(oldSscc, po.getPoNumber(), carton.getCartonNo(), reason));
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.SSCC_UNASSIGNED, cartonId);
        return carton;
    }

    public List<TraceResult> trace(String buyerCode, String orderId, String keyword) {
        requireOrder(buyerCode, orderId);
        String raw = clean(keyword);
        if (raw == null) throw new IllegalArgumentException(AppMessages.TRACE_KEY_REQUIRED);
        String skuKey = normalizeSku(raw);
        LinkedHashMap<String, Carton> matchedCartons = new LinkedHashMap<>();
        LinkedHashMap<String, PurchaseOrder> matchedPos = new LinkedHashMap<>();

        String canonicalSscc = SsccNormalizer.normalize(raw);
        if (canonicalSscc != null) cartonRepository.findBySscc18(canonicalSscc).ifPresent(carton -> {
            if (orderId.equals(carton.getOrderId()) && AppConstants.Buyer.LULULEMON.equals(carton.getBuyerCode()) && canAccessFactoryOrUnassigned(carton.getFactoryCode())) {
                matchedCartons.put(carton.getId(), carton);
            }
        });
        cartonRepository.findById(raw).ifPresent(carton -> {
            if (orderId.equals(carton.getOrderId()) && AppConstants.Buyer.LULULEMON.equals(carton.getBuyerCode()) && canAccessFactoryOrUnassigned(carton.getFactoryCode())) {
                matchedCartons.put(carton.getId(), carton);
            }
        });

        String lowered = raw.toLowerCase(Locale.ROOT);
        for (PurchaseOrder po : poRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, AppConstants.Buyer.LULULEMON)) {
            if (!canAccessFactoryOrUnassigned(po.getFactoryCode())) continue;
            boolean poMatch = safe(po.getPoNumber()).toLowerCase(Locale.ROOT).contains(lowered)
                    || safe(po.getMasterPo()).toLowerCase(Locale.ROOT).contains(lowered)
                    || (skuKey != null && skuKey.equals(normalizeSku(po.getSku())));
            if (poMatch) matchedPos.put(po.getId(), po);
        }

        for (Carton carton : matchedCartons.values()) {
            PurchaseOrder po = requirePo(orderId, carton.getPoId());
            matchedPos.put(po.getId(), po);
        }

        List<TraceResult> results = new ArrayList<>();
        if (!matchedCartons.isEmpty()) {
            for (Carton carton : matchedCartons.values()) {
                PurchaseOrder po = requirePo(orderId, carton.getPoId());
                List<CartonItem> items = itemRepository.findByCartonIdOrderByItemNoAsc(carton.getId());
                List<ScanEvent> events = scanEventRepository.findTop200ByCartonIdOrderByCreatedAtDesc(carton.getId());
                results.add(new TraceResult(po, carton, items, events, weightEventsForCarton(carton.getId())));
            }
            return results;
        }

        for (PurchaseOrder po : matchedPos.values()) {
            List<Carton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId());
            if (cartons.isEmpty()) {
                results.add(new TraceResult(po, null, List.of(), scanEventRepository.findTop200ByPoIdOrderByCreatedAtDesc(po.getId()), weightEventsForPo(po.getId())));
                continue;
            }
            for (Carton carton : cartons) {
                results.add(new TraceResult(
                        po, carton, itemRepository.findByCartonIdOrderByItemNoAsc(carton.getId()),
                        scanEventRepository.findTop200ByCartonIdOrderByCreatedAtDesc(carton.getId()),
                        weightEventsForCarton(carton.getId())
                ));
            }
        }
        return results.stream().limit(200).toList();
    }

    private List<WeighingResult> weightEventsForCarton(String cartonId) {
        if (clean(cartonId) == null) return List.of();
        Query query = Query.query(Criteria.where("cartonId").is(cartonId))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(200);
        return mongoTemplate.find(query, WeighingResult.class);
    }

    private List<WeighingResult> weightEventsForPo(String poId) {
        if (clean(poId) == null) return List.of();
        Query query = Query.query(Criteria.where("poId").is(poId))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(200);
        return mongoTemplate.find(query, WeighingResult.class);
    }

    public List<ScanEvent> recentEvents(String buyerCode, String orderId) {
        requireOrder(buyerCode, orderId);
        Set<String> allowedFactories = new HashSet<>(factoryAccess.accessibleFactories());
        // Factory is mandatory on new Orders; unassigned historical events are Admin-only.
        boolean unrestricted = factoryAccess.isUnrestricted();
        Map<String, String> poFactory = poRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, AppConstants.Buyer.LULULEMON).stream()
                .collect(Collectors.toMap(PurchaseOrder::getId, PurchaseOrder::getFactoryCode, (a, b) -> a));
        return scanEventRepository.findTop100ByOrderIdAndBuyerCodeOrderByCreatedAtDesc(orderId, AppConstants.Buyer.LULULEMON).stream()
                .filter(event -> unrestricted || (event.getPoId() != null && allowedFactories.contains(normalizeFactory(poFactory.get(event.getPoId())))))
                .toList();
    }

    private PoSummary summary(PurchaseOrder po) {
        List<Carton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId());
        int finished = (int) cartons.stream().filter(c -> AppConstants.CartonStatus.FINISHED.equals(c.getStatus()) || AppConstants.CartonStatus.LABEL_CONFIRMED.equals(c.getStatus()) || AppConstants.CartonStatus.READY_TO_SHIP.equals(c.getStatus())).count();
        int assigned = (int) cartons.stream().filter(c -> clean(c.getSscc()) != null).count();
        CartonScanProgress progress = CartonScanProgress.from(po, cartons);
        return new PoSummary(po, finished, assigned, progress.scannedQty(), progress.scannedCartons(),
                progress.scannedFullCartons(), progress.scannedRemainderQty(), progress.scannedRemainderCartons(),
                progress.identifiedQty(), progress.identifiedCartons(), progress.identifiedFullCartons(),
                progress.identifiedRemainderQty(), progress.identifiedRemainderCartons(),
                progress.completedQty(), progress.completedCartons(), progress.completedFullCartons(),
                progress.completedRemainderQty(), progress.completedRemainderCartons());
    }

    private void refreshPoStatus(PurchaseOrder po) {
        List<Carton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId());
        if (cartons.isEmpty()) {
            po.setStatus(AppConstants.PurchaseOrderStatus.NOT_STARTED);
        } else if (cartons.stream().allMatch(c -> AppConstants.CartonStatus.READY_TO_SHIP.equals(c.getStatus()) && clean(c.getSscc()) != null)) {
            po.setStatus(AppConstants.PurchaseOrderStatus.READY_TO_SHIP);
        } else if (cartons.stream().allMatch(c ->
                AppConstants.CartonStatus.FINISHED.equals(c.getStatus())
                        || AppConstants.CartonStatus.LABEL_CONFIRMED.equals(c.getStatus())
                        || AppConstants.CartonStatus.READY_TO_SHIP.equals(c.getStatus()))) {
            if (po.getExFtyDate() == null) {
                po.setStatus(AppConstants.PurchaseOrderStatus.WAITING_EX_FTY);
            } else if (cartons.stream().anyMatch(c -> AppConstants.CartonStatus.LABEL_CONFIRMED.equals(c.getStatus()) || clean(c.getSscc()) != null)) {
                po.setStatus(AppConstants.PurchaseOrderStatus.WAITING_SSCC);
            } else {
                po.setStatus(AppConstants.PurchaseOrderStatus.WAITING_LABEL);
            }
        } else if (cartons.stream().anyMatch(c -> safeInt(c.getScannedQty()) > 0 || AppConstants.CartonStatus.PACKING.equals(c.getStatus()))) {
            po.setStatus(AppConstants.PurchaseOrderStatus.PACKING);
        } else {
            po.setStatus(AppConstants.PurchaseOrderStatus.NOT_STARTED);
        }
        po.setUpdatedAt(LocalDateTime.now());
        po.setUpdatedBy(RequestActor.current());
        poRepository.save(po);
    }

    private List<Carton> ensureCartonsGenerated(PurchaseOrder po) {
        List<Carton> existing = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId());
        if (!existing.isEmpty()) return existing;

        int totalQty = safeInt(po.getTotalQty());
        int qtyPerCarton = safeInt(po.getQtyPerCarton());
        if (totalQty <= 0 || qtyPerCarton <= 0) {
            throw new IllegalArgumentException(AppMessages.poPlanningInvalid(po.getPoNumber()));
        }

        LocalDateTime now = LocalDateTime.now();
        String actor = RequestActor.current();
        List<Integer> quantities = po.getCartonPlannedQuantities() == null || po.getCartonPlannedQuantities().isEmpty()
                ? cartonQuantities(totalQty, qtyPerCarton)
                : List.copyOf(po.getCartonPlannedQuantities());
        List<Carton> batch = new ArrayList<>(quantities.size());
        for (int index = 0; index < quantities.size(); index++) {
            Carton carton = new Carton();
            carton.setBuyerCode(AppConstants.Buyer.LULULEMON);
            carton.setOrderId(po.getOrderId());
            carton.setPoId(po.getId());
            carton.setPoNumber(po.getPoNumber());
            carton.setFactoryCode(po.getFactoryCode());
            carton.setCartonNo(index + 1);
            carton.setPlannedQty(quantities.get(index));
            carton.setScannedQty(0);
            carton.setStatus(AppConstants.CartonStatus.READY_TO_PACK);
            carton.setUpdatedBy(actor);
            carton.setUpdatedAt(now);
            carton.setCreatedAt(now);
            batch.add(carton);
        }
        try {
            cartonRepository.saveAll(batch);
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // Another request generated the same PO structure concurrently.
        }
        return cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId());
    }

    private void ensureItemsGenerated(Carton carton) {
        if (itemRepository.existsByCartonId(carton.getId())) return;
        int plannedQty = safeInt(carton.getPlannedQty());
        if (plannedQty <= 0) return;

        LocalDateTime now = LocalDateTime.now();
        List<CartonItem> batch = new ArrayList<>(plannedQty);
        for (int itemNo = 1; itemNo <= plannedQty; itemNo++) {
            CartonItem item = new CartonItem();
            item.setBuyerCode(AppConstants.Buyer.LULULEMON);
            item.setOrderId(carton.getOrderId());
            item.setPoId(carton.getPoId());
            item.setPoNumber(carton.getPoNumber());
            item.setCartonId(carton.getId());
            item.setCartonNo(carton.getCartonNo());
            item.setItemNo(itemNo);
            String identifiedSku = normalizeSku(carton.getLabelConfirmedSku());
            boolean identified = carton.getLabelConfirmedAt() != null && identifiedSku != null;
            item.setStatus(identified ? AppConstants.ItemStatus.IDENTIFIED : AppConstants.ItemStatus.PENDING);
            item.setRfidStatus(identified ? AppConstants.RfidStatus.WAITING_RFID : null);
            // If the carton was already identified, newly materialized item slots
            // inherit the expected SKU while RFID verification remains separate.
            item.setExpectedSku(identifiedSku);
            item.setCreatedAt(now);
            item.setUpdatedAt(now);
            batch.add(item);
        }
        try {
            itemRepository.saveAll(batch);
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // Another request generated the same Carton items concurrently.
        }
    }

    /**
     * Backward-compatible normalization for cartons identified by earlier builds.
     * Existing item rows may still say PENDING even though expectedSku was already applied.
     * Normalize them once when the carton is opened so persisted state matches the workflow.
     */
    private List<CartonItem> syncItemIdentificationState(Carton carton, List<CartonItem> items) {
        String identifiedSku = normalizeSku(carton.getLabelConfirmedSku());
        boolean cartonIdentified = carton.getLabelConfirmedAt() != null && identifiedSku != null;
        if (!cartonIdentified || items == null || items.isEmpty()) return items;

        boolean changed = false;
        LocalDateTime now = LocalDateTime.now();
        for (CartonItem item : items) {
            boolean itemChanged = false;
            if (normalizeSku(item.getExpectedSku()) == null) {
                item.setExpectedSku(identifiedSku);
                itemChanged = true;
            }
            if (AppConstants.ItemStatus.PENDING.equals(item.getStatus())) {
                item.setStatus(AppConstants.ItemStatus.IDENTIFIED);
                itemChanged = true;
            }
            if (!AppConstants.ItemStatus.PASS.equals(item.getStatus()) && clean(item.getRfidStatus()) == null) {
                item.setRfidStatus(AppConstants.RfidStatus.WAITING_RFID);
                itemChanged = true;
            }
            if (itemChanged) {
                item.setUpdatedAt(now);
                changed = true;
            }
        }
        if (changed) return itemRepository.saveAll(items);
        return items;
    }

    private boolean canAccessFactoryOrUnassigned(String factoryCode) {
        String normalized = normalizeFactory(factoryCode);
        // Legacy factory-less data remains Admin-only, not visible to regular users.
        if (normalized == null) return factoryAccess.isUnrestricted();
        return factoryAccess.canAccessFactory(normalized)
                || (factoryAccess.currentUser().canWeightCheck()
                    && factoryAccess.canAccessWeighingFactory(normalized));
    }

    private void assertFactoryAccessIfPresent(String factoryCode) {
        String normalized = normalizeFactory(factoryCode);
        if (normalized != null && !factoryAccess.canAccessFactory(normalized)
                && !(factoryAccess.currentUser().canWeightCheck()
                    && factoryAccess.canAccessWeighingFactory(normalized)))
            throw new org.springframework.security.access.AccessDeniedException("Factory access denied: " + normalized);
    }

    private PackingOrder requireOrder(String buyerCode, String orderId) {
        String buyer = BuyerAccess.normalize(buyerCode);
        if (!AppConstants.Buyer.LULULEMON.equals(buyer)) throw new IllegalArgumentException(AppMessages.workflowOnlyFor(AppConstants.Buyer.LULULEMON));
        return orderService.getEntity(AppConstants.Buyer.LULULEMON, orderId);
    }

    private PurchaseOrder requirePo(String orderId, String poId) {
        return poRepository.findByIdAndOrderIdAndBuyerCode(poId, orderId, AppConstants.Buyer.LULULEMON)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.entityNotFound(AppConstants.Buyer.LULULEMON + " PO")));
    }

    private Carton requireCarton(String orderId, String cartonId) {
        return cartonRepository.findByIdAndOrderIdAndBuyerCode(cartonId, orderId, AppConstants.Buyer.LULULEMON)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.entityNotFound(AppConstants.Buyer.LULULEMON + " carton")));
    }

    private void assertPoNotAssignedToOpenSchedule(PurchaseOrder po) {
        List<ShippingSchedule> blockingSchedules = shippingScheduleRepository
                .findByOrderIdAndBuyerCodeOrderByExFtyDateAscCreatedAtDesc(po.getOrderId(), AppConstants.Buyer.LULULEMON).stream()
                .filter(schedule -> !"COMPLETED".equals(schedule.getStatus()))
                .filter(schedule -> schedule.getPoIds() != null && schedule.getPoIds().contains(po.getId()))
                .toList();
        if (blockingSchedules.isEmpty()) return;

        ShippingSchedule schedule = blockingSchedules.get(0);
        throw new IllegalArgumentException("PO " + po.getPoNumber()
                + " is already assigned to Shipping Schedule " + schedule.getScheduleNo()
                + ". Reassign or remove it from Shipping Schedule before editing or deleting this PO.");
    }

    private void assertPoWeighingNotCompleted(PurchaseOrder po) {
        List<Carton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId());
        if (AppConstants.WeighingSessionStatus.COMPLETED.equals(po.getWeighingSessionStatus())
                || po.getWeighingCompletedAt() != null
                || (!cartons.isEmpty() && cartons.stream().allMatch(c -> AppConstants.WeightStatus.PASS.equals(c.getWeightStatus())))) {
            throw new IllegalArgumentException("This PO has completed Carton Weight and is locked from Sales edits.");
        }
    }

    private boolean isAssignedToAnySchedule(PurchaseOrder po) {
        return shippingScheduleRepository
                .findByOrderIdAndBuyerCodeOrderByExFtyDateAscCreatedAtDesc(po.getOrderId(), AppConstants.Buyer.LULULEMON).stream()
                .anyMatch(s -> s.getPoIds() != null && s.getPoIds().contains(po.getId()));
    }

    private boolean hasPoOperationalActivity(PurchaseOrder po) {
        List<Carton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId());
        return clean(po.getSku()) != null || cartons.stream().anyMatch(c ->
                safeInt(c.getScannedQty()) > 0 || clean(c.getSscc()) != null
                        || c.getStartedAt() != null || c.getFinishedAt() != null
                        || c.getLabelConfirmedAt() != null || c.getSsccAssignedAt() != null
                        || c.getWeighedAt() != null || clean(c.getWeightStatus()) != null
                        || (clean(c.getStatus()) != null && !AppConstants.CartonStatus.READY_TO_PACK.equals(c.getStatus())));
    }

    private void assertImportedPoMutable(PurchaseOrder po) {
        List<Carton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId());
        boolean operationalActivity = clean(po.getSku()) != null || cartons.stream().anyMatch(carton ->
                safeInt(carton.getScannedQty()) > 0
                        || clean(carton.getSscc()) != null
                        || carton.getStartedAt() != null
                        || carton.getFinishedAt() != null
                        || carton.getLabelConfirmedAt() != null
                        || carton.getSsccAssignedAt() != null
                        || (clean(carton.getStatus()) != null && !AppConstants.CartonStatus.READY_TO_PACK.equals(carton.getStatus()))
        );
        if (operationalActivity) {
            throw new IllegalArgumentException(AppMessages.PO_OPERATION_LOCKED);
        }

    }

    private void assertReplaceAllowed(String orderId) {
        List<PurchaseOrder> existingPos = poRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, AppConstants.Buyer.LULULEMON);
        if (existingPos.isEmpty()) return;
        boolean started = existingPos.stream().anyMatch(po -> clean(po.getSku()) != null)
                || cartonRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAscCartonNoAsc(orderId, AppConstants.Buyer.LULULEMON).stream()
                .anyMatch(carton -> safeInt(carton.getScannedQty()) > 0 || clean(carton.getSscc()) != null
                        || !AppConstants.CartonStatus.READY_TO_PACK.equals(carton.getStatus()));
        if (started) {
            throw new IllegalArgumentException(AppMessages.ALL_BP_REPLACE_LOCKED);
        }
    }

    private int findHeaderRow(Sheet sheet, FormulaEvaluator evaluator) {
        int limit = Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 12);
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= limit; rowIndex++) {
            Map<String, Integer> columns = resolveAllBpColumns(sheet.getRow(rowIndex), evaluator);
            if (columns.containsKey(AppConstants.AllBpKey.PO) && columns.containsKey(AppConstants.AllBpKey.STYLE)
                    && columns.containsKey(AppConstants.AllBpKey.QTY) && columns.containsKey(AppConstants.AllBpKey.PCS_CTN)) {
                return rowIndex;
            }
        }
        throw new IllegalArgumentException(AppMessages.ALL_BP_HEADER_NOT_FOUND);
    }

    private int findShippingHeaderRow(Sheet sheet, FormulaEvaluator evaluator) {
        int limit = Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 15);
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= limit; rowIndex++) {
            Map<String, Integer> columns = headerColumns(sheet.getRow(rowIndex), evaluator);
            if (optionalColumn(columns, AppConstants.AllBpKey.PO, "PONO", "PURCHASEORDER") != null
                    && optionalColumn(columns, "EXFTYDATE", "EXFACTORYDATE", "EXFTY", "EXFACTORY") != null) {
                return rowIndex;
            }
        }
        throw new IllegalArgumentException(AppMessages.SHIPPING_HEADERS_NOT_FOUND);
    }

    private Sheet findShippingSheet(Workbook workbook) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            String key = TextNormalizer.headerKey(sheet.getSheetName());
            if (key.contains("SHIPPING") || key.contains("SHIPLIST") || key.contains("ALLBP")) return sheet;
        }
        return workbook.getSheetAt(0);
    }

    private String canonicalAllBpKey(String rawHeader) {
        String cleanHeader = clean(rawHeader);
        if (cleanHeader == null) return null;
        String asciiHeader = Normalizer.normalize(cleanHeader, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        String key = TextNormalizer.headerKey(asciiHeader);
        if (key == null || key.isEmpty()) return null;
        for (Map.Entry<String, Set<String>> entry : AppConstants.AllBp.ALIASES.entrySet()) {
            if (entry.getValue().contains(key)) return entry.getKey();
        }
        return null;
    }

    private Map<String, Integer> resolveAllBpColumns(Row row, FormulaEvaluator evaluator) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (row == null || row.getLastCellNum() < 0) return result;
        for (int col = 0; col < row.getLastCellNum(); col++) {
            String rawHeader = clean(excelSupport.text(row, col, evaluator));
            String canonical = canonicalAllBpKey(rawHeader);
            if (canonical == null) continue;
            Integer previous = result.putIfAbsent(canonical, col);
            if (previous != null && previous != col) {
                throw new IllegalArgumentException(AppMessages.duplicateAllBpColumn(canonical, previous + 1, col + 1));
            }
        }
        return result;
    }

    private int requiredCanonicalColumn(Map<String, Integer> columns, String key) {
        Integer col = columns.get(key);
        if (col == null) throw new IllegalArgumentException(AppMessages.missingAllBpColumn(key));
        return col;
    }

    private String canonicalValue(Row row, Map<String, Integer> columns, FormulaEvaluator evaluator, String key) {
        Integer col = columns.get(key);
        return col == null ? null : clean(excelSupport.text(row, col, evaluator));
    }

    private Double canonicalDecimalValue(Row row, Map<String, Integer> columns, FormulaEvaluator evaluator, String key) {
        Integer col = columns.get(key);
        if (col == null) return null;
        BigDecimal value = excelSupport.decimal(row, col, evaluator);
        return value == null ? null : value.doubleValue();
    }

    private List<SourceColumn> sourceColumns(Row header, FormulaEvaluator evaluator) {
        List<SourceColumn> result = new ArrayList<>();
        if (header == null || header.getLastCellNum() < 0) return result;
        Map<String, Integer> seenLabels = new LinkedHashMap<>();
        for (int col = 0; col < header.getLastCellNum(); col++) {
            String label = clean(excelSupport.text(header, col, evaluator));
            if (label == null) continue;
            int occurrence = seenLabels.merge(label, 1, Integer::sum);
            String displayLabel = occurrence == 1 ? label : label + " [" + occurrence + "]";
            result.add(new SourceColumn(displayLabel, col));
        }
        return result;
    }

    private Map<String, String> captureAllBpRow(Row row, List<SourceColumn> sourceColumns, FormulaEvaluator evaluator) {
        Map<String, String> result = new LinkedHashMap<>();
        for (SourceColumn sourceColumn : sourceColumns) {
            String canonical = canonicalAllBpKey(sourceColumn.label());
            String text = clean(excelSupport.text(row, sourceColumn.index(), evaluator));
            // Preserve the actual numeric value for calculated/price columns. This is required
            // because the current ALL_BP formats U:AA as ";;" (visually hidden), while Q is
            // formatted as an integer even though =O/P can contain decimals.
            if (AppConstants.AllBp.NUMERIC_SOURCE_KEYS.contains(canonical) || text == null) {
                try {
                    BigDecimal numeric = excelSupport.decimal(row, sourceColumn.index(), evaluator);
                    if (numeric != null) text = plainDecimal(numeric);
                } catch (RuntimeException ignored) {
                    // A genuinely blank/non-numeric cell keeps its formatted text/blank value.
                }
            }
            result.put(sourceColumn.label(), text);
        }
        return result;
    }

    private Map<String, Integer> headerColumns(Row row, FormulaEvaluator evaluator) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (row == null) return result;
        short last = row.getLastCellNum();
        if (last < 0) return result;
        for (int col = 0; col < last; col++) {
            String key = TextNormalizer.headerKey(excelSupport.text(row, col, evaluator));
            if (!key.isEmpty()) result.putIfAbsent(key, col);
        }
        return result;
    }

    private int requiredColumn(Map<String, Integer> columns, String... aliases) {
        Integer value = optionalColumn(columns, aliases);
        if (value == null) throw new IllegalArgumentException(AppMessages.missingExcelColumn(aliases));
        return value;
    }

    private Integer optionalColumn(Map<String, Integer> columns, String... aliases) {
        for (String alias : aliases) {
            String key = TextNormalizer.headerKey(alias);
            Integer exact = columns.get(key);
            if (exact != null) return exact;
            for (Map.Entry<String, Integer> entry : columns.entrySet()) {
                if (entry.getKey().startsWith(key) || key.startsWith(entry.getKey())) return entry.getValue();
            }
        }
        return null;
    }

    private String value(Row row, Map<String, Integer> columns, FormulaEvaluator evaluator, String alias) {
        Integer col = optionalColumn(columns, alias);
        return col == null ? null : clean(excelSupport.text(row, col, evaluator));
    }

    private String firstValue(Row row, Map<String, Integer> columns, FormulaEvaluator evaluator, String... aliases) {
        Integer col = optionalColumn(columns, aliases);
        return col == null ? null : clean(excelSupport.text(row, col, evaluator));
    }

    private Double decimalValue(Row row, Map<String, Integer> columns, FormulaEvaluator evaluator, String... aliases) {
        Integer col = optionalColumn(columns, aliases);
        if (col == null) return null;
        BigDecimal value = excelSupport.decimal(row, col, evaluator);
        return value == null ? null : value.doubleValue();
    }

    private EditedAllBpPlan buildEditedAllBpPlan(PurchaseOrder po, PoUpdateRequest request) {
        List<Map<String, String>> submitted = request.allBpRows();
        if (submitted == null || submitted.isEmpty()) return null;

        List<Map<String, String>> existing = po.getAllBpRows() == null ? List.of() : po.getAllBpRows();
        if (!existing.isEmpty() && submitted.size() != existing.size()) {
            throw new IllegalArgumentException(AppMessages.ALL_BP_ROW_COUNT_IMMUTABLE);
        }

        List<String> headers = po.getAllBpHeaders() == null ? new ArrayList<>() : new ArrayList<>(po.getAllBpHeaders());
        if (headers.isEmpty()) {
            LinkedHashSet<String> discovered = new LinkedHashSet<>();
            existing.forEach(row -> { if (row != null) discovered.addAll(row.keySet()); });
            submitted.forEach(row -> { if (row != null) discovered.addAll(row.keySet()); });
            headers.addAll(discovered);
        }

        List<Map<String, String>> rows = new ArrayList<>(submitted.size());
        List<Integer> quantities = new ArrayList<>();
        String poNumber = null;
        String style = null;
        Integer qtyPerCarton = null;
        int lastRowPcsPerCarton = 0;
        int totalQty = 0;

        Map<String, String> shared = new LinkedHashMap<>();
        // A merged ALL_BP record is defined by exactly these business dimensions
        // in addition to PO + Style, which are validated separately below.
        List<String> mustMatch = List.of(
                AppConstants.AllBpKey.DC_CODE,
                AppConstants.AllBpKey.DESTINATION,
                AppConstants.AllBpKey.CHANNEL,
                AppConstants.AllBpKey.MASTER_PO,
                AppConstants.AllBpKey.PACKING_PLAN,
                AppConstants.AllBpKey.SALES_ORDER_PTS,
                AppConstants.AllBpKey.DESCRIPTION,
                AppConstants.AllBpKey.COLOR,
                AppConstants.AllBpKey.SIZE
        );

        for (int index = 0; index < submitted.size(); index++) {
            Map<String, String> base = index < existing.size() && existing.get(index) != null
                    ? existing.get(index) : Map.of();
            Map<String, String> incoming = submitted.get(index) == null ? Map.of() : submitted.get(index);
            Map<String, String> edited = new LinkedHashMap<>();

            for (String header : headers) {
                String canonical = canonicalAllBpKey(header);
                String original = base.get(header);
                if (canonical != null && AppConstants.AllBp.FORMULA_KEYS.contains(canonical)) {
                    // Never accept a client-supplied calculated value.
                    edited.put(header, original);
                } else {
                    edited.put(header, normalizeCellValue(incoming.containsKey(header) ? incoming.get(header) : original));
                }
            }
            // Preserve any legacy/source columns that were not present in allBpHeaders.
            for (Map.Entry<String, String> entry : base.entrySet()) {
                edited.putIfAbsent(entry.getKey(), entry.getValue());
            }

            int rowNo = index + 1;
            String rowPo = requiredRowText(edited, AppConstants.AllBpKey.PO, AppConstants.AllBpKey.PO, rowNo);
            String rowStyle = requiredRowText(edited, AppConstants.AllBpKey.STYLE, "Style#", rowNo);
            int rowQty = requiredPositiveWhole(rowCanonicalValue(edited, AppConstants.AllBpKey.QTY), "Q'ty", rowNo);
            int rowPcs = requiredPositiveWhole(rowCanonicalValue(edited, AppConstants.AllBpKey.PCS_CTN), "Pcs/ctn", rowNo);

            recalculateAllBpFormulaColumns(edited, rowQty, rowPcs, rowNo);
            quantities.addAll(cartonQuantities(rowQty, rowPcs));
            totalQty = Math.addExact(totalQty, rowQty);

            if (poNumber == null) poNumber = rowPo;
            else if (!Objects.equals(poNumber, rowPo)) {
                throw new IllegalArgumentException(AppMessages.ALL_BP_PO_NUMBER_MUST_MATCH);
            }
            if (style == null) style = rowStyle;
            else if (!Objects.equals(style, rowStyle)) {
                throw new IllegalArgumentException(AppMessages.ALL_BP_STYLE_MUST_MATCH);
            }
            if (qtyPerCarton == null) qtyPerCarton = rowPcs;
            lastRowPcsPerCarton = rowPcs;

            for (String key : mustMatch) {
                String current = clean(rowCanonicalValue(edited, key));
                if (index == 0) shared.put(key, current);
                else if (!Objects.equals(shared.get(key), current)) {
                    throw new IllegalArgumentException(AppMessages.allBpFieldMustMatch(key));
                }
            }
            rows.add(new LinkedHashMap<>(edited));
        }

        if (quantities.isEmpty()) throw new IllegalArgumentException(AppMessages.EDITED_PO_CARTON_PLAN_REQUIRED);
        int lastQty = quantities.get(quantities.size() - 1);
        int remainder = lastRowPcsPerCarton > 0 && lastQty < lastRowPcsPerCarton ? lastQty : 0;
        Map<String, String> first = rows.get(0);

        Double nw = optionalDouble(rowCanonicalValue(first, "NW"), "NW");
        Double gw = optionalDouble(rowCanonicalValue(first, "GW"), "GW");
        if (nw != null && nw < 0) throw new IllegalArgumentException(AppMessages.cannotBeNegative(AppConstants.FieldLabel.NET_WEIGHT));
        if (gw != null && gw < 0) throw new IllegalArgumentException(AppMessages.cannotBeNegative(AppConstants.FieldLabel.GROSS_WEIGHT));

        return new EditedAllBpPlan(
                poNumber, style, totalQty, qtyPerCarton, List.copyOf(quantities), remainder,
                clean(rowCanonicalValue(first, AppConstants.AllBpKey.MASTER_PO)), clean(rowCanonicalValue(first, AppConstants.AllBpKey.DC_CODE)),
                clean(rowCanonicalValue(first, AppConstants.AllBpKey.DESTINATION)), clean(rowCanonicalValue(first, AppConstants.AllBpKey.CHANNEL)),
                clean(rowCanonicalValue(first, AppConstants.AllBpKey.PACKING_PLAN)), clean(rowCanonicalValue(first, AppConstants.AllBpKey.SALES_ORDER_PTS)),
                clean(rowCanonicalValue(first, AppConstants.AllBpKey.DESCRIPTION)), clean(rowCanonicalValue(first, AppConstants.AllBpKey.COLOR)),
                clean(rowCanonicalValue(first, AppConstants.AllBpKey.SIZE)),
                clean(rowCanonicalValue(first, AppConstants.AllBpKey.SHIP_MODE)), clean(rowCanonicalValue(first, AppConstants.AllBpKey.SEASON)),
                clean(rowCanonicalValue(first, AppConstants.AllBpKey.FWD)), clean(rowCanonicalValue(first, AppConstants.AllBpKey.CARTON_BOX_SIZE)),
                nw, gw, List.copyOf(rows)
        );
    }

    private void recalculateAllBpFormulaColumns(Map<String, String> row, int qty, int pcsPerCarton, int rowNo) {
        BigDecimal q = BigDecimal.valueOf(qty);
        BigDecimal p = BigDecimal.valueOf(pcsPerCarton);
        BigDecimal oddRatio = q.divide(p, 10, RoundingMode.HALF_UP);
        int fullCartons = qty / pcsPerCarton;
        int remainder = qty - (fullCartons * pcsPerCarton);

        BigDecimal fobPrice = optionalDecimalOrZero(rowCanonicalValue(row, "FOB_PRICE"), "FOB Price", rowNo, false);
        BigDecimal careLabel = optionalDecimalOrZero(rowCanonicalValue(row, "CARE_CONTENT_LABEL"), "Care & Content Label", rowNo, false);
        BigDecimal inspectionTag = optionalDecimalOrZero(rowCanonicalValue(row, "CHINA_INSPECTION_TAG"), "China Inspection Tag", rowNo, false);
        BigDecimal dpPercent = optionalDecimalOrZero(rowCanonicalValue(row, "DP_PERCENT"), "DP%", rowNo, true);

        BigDecimal priceTagTotal = fobPrice.add(careLabel).add(inspectionTag);
        BigDecimal fobAmount = q.multiply(fobPrice);
        BigDecimal totalFobPriceTag = q.multiply(priceTagTotal);
        BigDecimal fobFtyPrice = priceTagTotal.multiply(dpPercent).setScale(2, RoundingMode.HALF_UP);
        BigDecimal fobFtyAmount = q.multiply(fobFtyPrice);

        putCanonicalRowValue(row, "ODD_RATIO", plainDecimal(oddRatio));
        putCanonicalRowValue(row, "CTNS", Integer.toString(fullCartons));
        putCanonicalRowValue(row, "REMAINDER", Integer.toString(remainder));
        putCanonicalRowValue(row, "FOB_AMOUNT", plainDecimal(fobAmount));
        putCanonicalRowValue(row, "TOTAL_FOB_PRICE_TAG", plainDecimal(totalFobPriceTag));
        putCanonicalRowValue(row, "FOB_FTY_PRICE", fobFtyPrice.toPlainString());
        putCanonicalRowValue(row, "FOB_FTY_AMOUNT", plainDecimal(fobFtyAmount));
    }

    private String rowCanonicalValue(Map<String, String> row, String canonical) {
        if (row == null) return null;
        for (Map.Entry<String, String> entry : row.entrySet()) {
            if (Objects.equals(canonical, canonicalAllBpKey(entry.getKey()))) return entry.getValue();
        }
        return null;
    }

    private void putCanonicalRowValue(Map<String, String> row, String canonical, String value) {
        if (row == null) return;
        for (String header : new ArrayList<>(row.keySet())) {
            if (Objects.equals(canonical, canonicalAllBpKey(header))) {
                row.put(header, value);
                return;
            }
        }
    }

    private String requiredRowText(Map<String, String> row, String canonical, String label, int rowNo) {
        String value = clean(rowCanonicalValue(row, canonical));
        if (value == null) throw new IllegalArgumentException(AppMessages.allBpRowRequired(label, rowNo));
        return value;
    }

    private int requiredPositiveWhole(String raw, String label, int rowNo) {
        BigDecimal value = parseEditableDecimal(raw, label, rowNo, false);
        if (value == null) throw new IllegalArgumentException(AppMessages.allBpRowRequired(label, rowNo));
        try {
            int result = value.stripTrailingZeros().intValueExact();
            if (result <= 0) throw new IllegalArgumentException(AppMessages.allBpRowGreaterThanZero(label, rowNo));
            return result;
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(AppMessages.allBpRowWholeNumber(label, rowNo));
        }
    }

    private BigDecimal optionalDecimalOrZero(String raw, String label, int rowNo, boolean percent) {
        BigDecimal value = parseEditableDecimal(raw, label, rowNo, percent);
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal parseEditableDecimal(String raw, String label, int rowNo, boolean percent) {
        String value = clean(raw);
        if (value == null) return null;
        boolean explicitPercent = value.endsWith("%");
        String normalized = value.replace(",", "").replace("$", "").replace("%", "").trim();
        try {
            BigDecimal number = new BigDecimal(normalized);
            if (percent && (explicitPercent || number.abs().compareTo(BigDecimal.ONE) > 0)) {
                number = number.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
            }
            return number;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(AppMessages.allBpRowNumeric(label, rowNo, raw));
        }
    }

    private Double optionalDouble(String raw, String label) {
        String value = clean(raw);
        if (value == null) return null;
        try {
            return new BigDecimal(value.replace(",", "")).doubleValue();
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(AppMessages.numeric(label, raw));
        }
    }

    private String normalizeCellValue(String value) {
        return value == null ? null : value.trim();
    }

    private static String plainDecimal(BigDecimal value) {
        if (value == null) return null;
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) normalized = normalized.setScale(0);
        return normalized.toPlainString();
    }

    private List<Integer> cartonQuantities(int totalQty, int qtyPerCarton) {
        List<Integer> quantities = new ArrayList<>((totalQty + qtyPerCarton - 1) / qtyPerCarton);
        int remaining = totalQty;
        while (remaining > 0) {
            int qty = Math.min(qtyPerCarton, remaining);
            quantities.add(qty);
            remaining -= qty;
        }
        return quantities;
    }

    private int requiredPositiveInt(BigDecimal value, String field, int excelRow) {
        if (value == null) throw new IllegalArgumentException(AppMessages.excelRowRequired(field, excelRow));
        try {
            int result = value.stripTrailingZeros().intValueExact();
            if (result <= 0) throw new IllegalArgumentException(AppMessages.excelRowGreaterThanZero(field, excelRow));
            return result;
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(AppMessages.excelRowWholeNumber(field, excelRow, value));
        }
    }

    private String searchable(PurchaseOrder po) {
        return String.join(" ", safe(po.getPoNumber()), safe(po.getStyleNumber()), safe(po.getSku()), safe(po.getFactoryCode()),
                safe(po.getColor()), safe(po.getDescription())).toUpperCase(Locale.ROOT);
    }

    private String requireReason(ExceptionRequest request) {
        String reason = clean(request == null ? null : request.reason());
        if (reason == null || reason.length() < 3) {
            throw new IllegalArgumentException(AppMessages.EXCEPTION_REASON_REQUIRED);
        }
        return reason;
    }

    private void assertSkuAvailableForPo(PurchaseOrder targetPo, String sku) {
        if (sku == null || targetPo == null) return;

        // LULULEMON rule: SKU ownership belongs to one logical PO record only.
        // A raw PO number is NOT the identity of a PO because ALL_BP may contain
        // multiple records with the same PO number but different business-key fields.
        // Exact duplicates of the 10-field ALL_BP business key are merged during import,
        // therefore any other persisted PO id represents a different logical PO and
        // must use a different SKU -- even when poNumber is identical.
        poRepository.findByBuyerCodeAndSku(AppConstants.Buyer.LULULEMON, sku).stream()
                .filter(existing -> !Objects.equals(existing.getId(), targetPo.getId()))
                .findFirst()
                .ifPresent(existing -> {
                    throw new IllegalArgumentException(
                            AppMessages.skuAlreadyAssigned(sku, existing.getPoNumber())
                    );
                });
    }

    private String normalizeSku(String value) {
        String clean = clean(value);
        if (clean == null) return null;
        return clean.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private String normalizeSscc(String value) {
        String clean = clean(value);
        if (clean == null) return null;
        String normalized = SsccNormalizer.normalize(clean);
        if (normalized != null) return normalized;
        if (clean.replaceAll("\\D", "").isEmpty()) return null;
        throw new IllegalArgumentException(AppMessages.SSCC_LENGTH_INVALID);
    }

    private String normalizeFactory(String value) {
        String clean = clean(value);
        return clean == null ? null : clean.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private void passScan(String orderId, String poId, String cartonId, String type, String raw, String message) {
        scanEventRepository.save(event(orderId, poId, cartonId, type, raw, AppConstants.ScanResult.PASS, message));
    }

    private void rejectScan(String orderId, String poId, String cartonId, String type, String raw, String message) {
        scanEventRepository.save(event(orderId, poId, cartonId, type, raw, AppConstants.ScanResult.REJECT, message));
    }

    private ScanEvent event(String orderId, String poId, String cartonId, String type, String raw, String result, String message) {
        ScanEvent event = new ScanEvent();
        event.setBuyerCode(AppConstants.Buyer.LULULEMON);
        event.setOrderId(orderId);
        event.setPoId(poId);
        event.setCartonId(cartonId);
        event.setScanType(type);
        event.setRawValue(raw);
        event.setResult(result);
        event.setMessage(message);
        event.setActor(RequestActor.current());
        event.setCreatedAt(LocalDateTime.now());
        return event;
    }

    private void audit(String action, String resourceId, String description) {
        auditLogService.log(RequestActor.current(), action, AppConstants.AuditResource.CARTON_LOADING, resourceId, description, null, null);
    }

    private static int safeInt(Integer value) { return value == null ? 0 : value; }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String clean(String value) { return TextNormalizer.trimToNull(value); }
    private static String allBpBusinessKeyPart(String value) {
        String normalized = TextNormalizer.key(value);
        return normalized == null ? "" : normalized;
    }
    private static String cleanMessage(Exception ex) { return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }

    private record SourceColumn(String label, int index) { }

    private record EditedAllBpPlan(
            String poNumber,
            String styleNumber,
            int totalQty,
            int qtyPerCarton,
            List<Integer> cartonPlannedQuantities,
            int remainderQty,
            String masterPo,
            String dcCode,
            String destination,
            String channel,
            String packingPlan,
            String salesOrderPts,
            String description,
            String color,
            String size,
            String shipMode,
            String season,
            String fwd,
            String cartonBoxSize,
            Double netWeightKg,
            Double grossWeightKg,
            List<Map<String, String>> rows
    ) { }

    private record AllBpBusinessKey(
            String dcCode,
            String destination,
            String channel,
            String masterPo,
            String poNumber,
            String packingPlan,
            String salesOrderPts,
            String styleNumber,
            String description,
            String color,
            String size
    ) {
        static AllBpBusinessKey from(ParsedPo po) {
            return of(
                    po.dcCode(), po.destination(), po.channel(), po.masterPo(), po.poNumber(),
                    po.packingPlan(), po.salesOrderPts(), po.styleNumber(), po.description(), po.color(), po.size()
            );
        }

        static AllBpBusinessKey from(PurchaseOrder po) {
            return of(
                    po.getDcCode(), po.getDestination(), po.getChannel(), po.getMasterPo(), po.getPoNumber(),
                    po.getPackingPlan(), po.getSalesOrderPts(), po.getStyleNumber(), po.getDescription(), po.getColor(), po.getSize()
            );
        }

        static AllBpBusinessKey of(
                String dcCode, String destination, String channel, String masterPo, String poNumber,
                String packingPlan, String salesOrderPts, String styleNumber, String description, String color, String size
        ) {
            return new AllBpBusinessKey(
                    allBpBusinessKeyPart(dcCode),
                    allBpBusinessKeyPart(destination),
                    allBpBusinessKeyPart(channel),
                    allBpBusinessKeyPart(masterPo),
                    allBpBusinessKeyPart(poNumber),
                    allBpBusinessKeyPart(packingPlan),
                    allBpBusinessKeyPart(salesOrderPts),
                    allBpBusinessKeyPart(styleNumber),
                    allBpBusinessKeyPart(description),
                    allBpBusinessKeyPart(color),
                    allBpBusinessKeyPart(size)
            );
        }
    }

    private record ParsedPo(
            int sourceLineNo,
            String factoryCode,
            String poNumber,
            String styleNumber,
            int totalQty,
            int qtyPerCarton,
            int cartonCount,
            int remainderQty,
            List<Integer> cartonPlannedQuantities,
            String dcCode,
            String destination,
            String channel,
            String masterPo,
            String packingPlan,
            String salesOrderPts,
            String description,
            String color,
            String size,
            String shipMode,
            String season,
            String fwd,
            String cartonBoxSize,
            Double netWeight,
            Double grossWeight,
            List<Map<String, String>> allBpRows
    ) {
        ParsedPo mergeQuantity(ParsedPo other) {
            int combinedQty = Math.addExact(totalQty, other.totalQty);
            List<Integer> combinedQuantities = new ArrayList<>(cartonPlannedQuantities);
            combinedQuantities.addAll(other.cartonPlannedQuantities);
            List<Map<String, String>> combinedRows = new ArrayList<>(allBpRows);
            combinedRows.addAll(other.allBpRows);
            return new ParsedPo(
                    sourceLineNo, factoryCode, poNumber, styleNumber, combinedQty, qtyPerCarton,
                    combinedQuantities.size(), other.remainderQty(), List.copyOf(combinedQuantities),
                    dcCode, destination, channel, masterPo, packingPlan, salesOrderPts, description, color,
                    size, shipMode, season, fwd, cartonBoxSize, netWeight, grossWeight, List.copyOf(combinedRows)
            );
        }
    }

}
