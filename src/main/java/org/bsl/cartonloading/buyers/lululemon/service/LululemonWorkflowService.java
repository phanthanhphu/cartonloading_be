package org.bsl.cartonloading.buyers.lululemon.service;

import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.lululemon.dto.*;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonCarton;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonCartonItem;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonPo;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonPrintRequest;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonScanEvent;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonWeightEvent;
import org.bsl.cartonloading.buyers.lululemon.repository.LululemonCartonItemRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.LululemonCartonRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.LululemonPoRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.LululemonScanEventRepository;
import org.bsl.cartonloading.common.socket.AppSocketPublisher;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.buyers.es.service.PackingOrderService;
import org.bsl.cartonloading.service.RequestActor;
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
public class LululemonWorkflowService {
    private static final String BUYER = BuyerAccess.LULULEMON;
    private static final String ITEM_PENDING = "PENDING";
    private static final String ITEM_PASS = "PASS";

    private static final String PO_NOT_STARTED = "NOT_STARTED";
    private static final String PO_PACKING = "PACKING";
    private static final String PO_WAITING_EX_FTY = "WAITING_EX_FTY";
    private static final String PO_WAITING_LABEL = "WAITING_LABEL";
    private static final String PO_WAITING_SSCC = "WAITING_SSCC";
    private static final String PO_READY_TO_SHIP = "READY_TO_SHIP";

    private static final String CARTON_READY = "READY_TO_PACK";
    private static final String CARTON_PACKING = "PACKING";
    private static final String CARTON_FINISHED = "FINISHED";
    private static final String CARTON_LABEL_CONFIRMED = "LABEL_CONFIRMED";
    private static final String CARTON_READY_TO_SHIP = "READY_TO_SHIP";

    /**
     * Stable internal keys for ALL_BP. Excel column order is irrelevant; only an
     * exact normalized alias resolves a business field. Every source column is
     * also preserved verbatim in LululemonPo.allBpRows, including unknown future columns.
     */
    private static final Map<String, Set<String>> ALL_BP_ALIASES = createAllBpAliases();

    /** ALL_BP calculated columns: Q, R, S, X, Y, Z, AA. Never trust client edits for these fields. */
    private static final Set<String> ALL_BP_FORMULA_KEYS = Set.of(
            "ODD_RATIO", "CTNS", "REMAINDER", "FOB_AMOUNT",
            "TOTAL_FOB_PRICE_TAG", "FOB_FTY_PRICE", "FOB_FTY_AMOUNT"
    );

    private final LululemonPoRepository poRepository;
    private final LululemonCartonRepository cartonRepository;
    private final LululemonCartonItemRepository itemRepository;
    private final LululemonScanEventRepository scanEventRepository;
    private final PackingOrderService orderService;
    private final ExcelImportSupport excelSupport;
    private final BuyerFactoryAccessService factoryAccess;
    private final MongoTemplate mongoTemplate;
    private final AuditLogService auditLogService;
    private final AppSocketPublisher socketPublisher;

    public LululemonWorkflowService(
            LululemonPoRepository poRepository,
            LululemonCartonRepository cartonRepository,
            LululemonCartonItemRepository itemRepository,
            LululemonScanEventRepository scanEventRepository,
            PackingOrderService orderService,
            ExcelImportSupport excelSupport,
            BuyerFactoryAccessService factoryAccess,
            MongoTemplate mongoTemplate,
            AuditLogService auditLogService,
            AppSocketPublisher socketPublisher
    ) {
        this.poRepository = poRepository;
        this.cartonRepository = cartonRepository;
        this.itemRepository = itemRepository;
        this.scanEventRepository = scanEventRepository;
        this.orderService = orderService;
        this.excelSupport = excelSupport;
        this.factoryAccess = factoryAccess;
        this.mongoTemplate = mongoTemplate;
        this.auditLogService = auditLogService;
        this.socketPublisher = socketPublisher;
    }

    public LululemonImportResult importAllBp(String buyerCode, String orderId, MultipartFile file, boolean replace) {
        PackingOrder order = requireOrder(buyerCode, orderId);
        List<ParsedPo> parsed;
        List<String> warnings = new ArrayList<>();
        List<String> sourceHeaderLabels = List.of();

        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, "All_BP");
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            int headerRowIndex = findHeaderRow(sheet, evaluator);
            Row header = sheet.getRow(headerRowIndex);
            Map<String, Integer> columns = resolveAllBpColumns(header, evaluator);
            List<SourceColumn> sourceColumns = sourceColumns(header, evaluator);
            sourceHeaderLabels = sourceColumns.stream().map(SourceColumn::label).toList();

            int poCol = requiredCanonicalColumn(columns, "PO");
            int styleCol = requiredCanonicalColumn(columns, "STYLE");
            int qtyCol = requiredCanonicalColumn(columns, "QTY");
            int pcsCol = requiredCanonicalColumn(columns, "PCS_CTN");
            Integer factoryCol = columns.get("FACTORY");

            Map<String, ParsedPo> byPo = new LinkedHashMap<>();
            for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;
                String poNumber = clean(excelSupport.text(row, poCol, evaluator));
                if (poNumber == null) continue;

                String factory = factoryCol == null ? null : clean(excelSupport.text(row, factoryCol, evaluator));
                if (factory == null) factory = clean(order.getProductionFacility());
                if (factory == null) {
                    throw new IllegalArgumentException("Factory is required for PO " + poNumber
                            + ". Add a Factory/Production Facility column to All_BP or set Production Facility on the Order.");
                }
                factory = normalizeFactory(factory);

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
                        canonicalValue(row, columns, evaluator, "DC_CODE"),
                        canonicalValue(row, columns, evaluator, "DESTINATION"),
                        canonicalValue(row, columns, evaluator, "CHANNEL"),
                        canonicalValue(row, columns, evaluator, "MASTER_PO"),
                        canonicalValue(row, columns, evaluator, "PACKING_PLAN"),
                        canonicalValue(row, columns, evaluator, "SALES_ORDER_PTS"),
                        canonicalValue(row, columns, evaluator, "DESCRIPTION"),
                        canonicalValue(row, columns, evaluator, "COLOR"),
                        canonicalValue(row, columns, evaluator, "SIZE"),
                        canonicalValue(row, columns, evaluator, "SHIP_MODE"),
                        canonicalValue(row, columns, evaluator, "SEASON"),
                        canonicalValue(row, columns, evaluator, "FWD"),
                        canonicalValue(row, columns, evaluator, "CARTON_BOX_SIZE"),
                        canonicalDecimalValue(row, columns, evaluator, "NW"),
                        canonicalDecimalValue(row, columns, evaluator, "GW"),
                        List.of(captureAllBpRow(row, sourceColumns, evaluator))
                );

                ParsedPo previous = byPo.putIfAbsent(poNumber, candidate);
                if (previous != null) {
                    if (!previous.compatibleWith(candidate)) {
                        throw new IllegalArgumentException("PO " + poNumber + " appears more than once in All_BP with conflicting Factory/Style/Pcs per carton or shipping attributes (rows "
                                + previous.sourceLineNo() + " and " + candidate.sourceLineNo() + ").");
                    }
                    ParsedPo merged = previous.mergeQuantity(candidate);
                    byPo.put(poNumber, merged);
                    warnings.add("PO " + poNumber + " spans multiple All_BP rows; row " + candidate.sourceLineNo() + " quantity was added to the same PO.");
                }
            }
            parsed = new ArrayList<>(byPo.values());
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot import LULULEMON All_BP: " + cleanMessage(ex));
        }

        if (parsed.isEmpty()) throw new IllegalArgumentException("Sheet All_BP does not contain any PO rows.");
        if (!replace && !poRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, BUYER).isEmpty()) {
            throw new IllegalArgumentException("LULULEMON data already exists for this Order. Use replace=true before Packing starts.");
        }
        if (replace) assertReplaceAllowed(orderId);

        if (replace) {
            scanEventRepository.deleteByOrderIdAndBuyerCode(orderId, BUYER);
            itemRepository.deleteByOrderIdAndBuyerCode(orderId, BUYER);
            cartonRepository.deleteByOrderIdAndBuyerCode(orderId, BUYER);
            poRepository.deleteByOrderIdAndBuyerCode(orderId, BUYER);
        }

        LocalDateTime now = LocalDateTime.now();
        String actor = RequestActor.current();
        List<LululemonPo> poBatch = new ArrayList<>(parsed.size());

        for (ParsedPo parsedPo : parsed) {
            LululemonPo po = new LululemonPo();
            po.setBuyerCode(BUYER);
            po.setOrderId(orderId);
            po.setSourceLineNo(parsedPo.sourceLineNo());
            po.setFactoryCode(parsedPo.factoryCode());
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
            po.setStatus(PO_NOT_STARTED);
            po.setCreatedBy(actor);
            po.setUpdatedBy(actor);
            po.setCreatedAt(now);
            po.setUpdatedAt(now);
            poBatch.add(po);
        }
        poRepository.saveAll(poBatch);

        warnings.add("Lazy generation enabled: import creates PO records only. Cartons are generated when a PO is opened; Items are generated when a Carton is opened.");
        audit("LULULEMON_ALL_BP_IMPORT", orderId,
                "Imported " + parsed.size() + " PO(s) from All_BP. Carton/Item generation is deferred until the user opens them.");
        socketPublisher.cartonLoadingChanged("LULULEMON_ALL_BP_IMPORTED", orderId);
        return new LululemonImportResult(true, parsed.size(), 0, 0, 0, warnings);
    }

    public Page<LululemonPoSummary> listPos(String buyerCode, String orderId, String keyword, String status, int page, int size) {
        requireOrder(buyerCode, orderId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));

        Criteria criteria = Criteria.where("orderId").is(orderId).and("buyerCode").is(BUYER);
        if (!factoryAccess.isUnrestricted()) {
            Set<String> allowedFactories = new HashSet<>(factoryAccess.accessibleFactories());
            criteria = criteria.and("factoryCode").in(allowedFactories);
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
        long total = mongoTemplate.count(countQuery, LululemonPo.class);
        Query dataQuery = Query.query(criteria)
                .with(Sort.by(Sort.Direction.ASC, "poNumber"))
                .skip((long) safePage * safeSize)
                .limit(safeSize);
        List<LululemonPoSummary> rows = mongoTemplate.find(dataQuery, LululemonPo.class).stream()
                .map(this::summary)
                .toList();
        Pageable pageable = PageRequest.of(safePage, safeSize);
        return new PageImpl<>(rows, pageable, total);
    }

    public LululemonPoDetailResponse getPo(String buyerCode, String orderId, String poId) {
        requireOrder(buyerCode, orderId);
        LululemonPo po = requirePo(orderId, poId);
        factoryAccess.assertFactoryAccess(po.getFactoryCode());
        List<LululemonCarton> cartons = ensureCartonsGenerated(po);
        return new LululemonPoDetailResponse(po, cartons);
    }

    /**
     * Edit the imported PO source record. Cartons/Items are derived data and are never
     * directly edited here. ALL_BP formula columns are always recalculated on the backend.
     *
     * If generated Cartons/Items already existed (but no operation has started), their
     * structure is rebuilt from the edited PO immediately so stale generated data cannot
     * remain visible. If they had never been generated, lazy generation is preserved.
     */
    public synchronized LululemonPo updateImportedPo(
            String buyerCode, String orderId, String poId, LululemonPoUpdateRequest request
    ) {
        requireOrder(buyerCode, orderId);
        LululemonPo po = requirePo(orderId, poId);
        factoryAccess.assertFactoryAccess(po.getFactoryCode());
        assertImportedPoMutable(po);
        if (request == null) throw new IllegalArgumentException("PO data is required.");

        List<LululemonCarton> generatedBeforeEdit = cartonRepository.findByPoIdOrderByCartonNoAsc(poId);
        boolean hadGeneratedCartons = !generatedBeforeEdit.isEmpty();
        boolean hadGeneratedItems = generatedBeforeEdit.stream().anyMatch(carton -> itemRepository.existsByCartonId(carton.getId()));

        EditedAllBpPlan allBpPlan = buildEditedAllBpPlan(po, request);

        String factoryCode = normalizeFactory(request.factoryCode());
        if (factoryCode == null) factoryCode = normalizeFactory(po.getFactoryCode());
        if (factoryCode == null) throw new IllegalArgumentException("Factory is required.");
        factoryAccess.assertFactoryAccess(factoryCode);

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
            if (poNumber == null) throw new IllegalArgumentException("PO No. is required.");
            if (style == null) throw new IllegalArgumentException("Style is required.");
            if (totalQty <= 0) throw new IllegalArgumentException("Total Qty must be greater than 0.");
            if (qtyPerCarton <= 0) throw new IllegalArgumentException("Pcs / Ctn must be greater than 0.");
            if (request.netWeightKg() != null && request.netWeightKg() < 0) throw new IllegalArgumentException("N.W. cannot be negative.");
            if (request.grossWeightKg() != null && request.grossWeightKg() < 0) throw new IllegalArgumentException("G.W. cannot be negative.");

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

        poRepository.findByOrderIdAndBuyerCodeAndPoNumber(orderId, BUYER, poNumber)
                .filter(existing -> !Objects.equals(existing.getId(), po.getId()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("PO " + poNumber + " already exists in this Order.");
                });

        po.setPoNumber(poNumber);
        po.setFactoryCode(factoryCode);
        po.setStyleNumber(style);
        po.setTotalQty(totalQty);
        po.setQtyPerCarton(qtyPerCarton);
        po.setCartonCount(plannedQuantities.size());
        po.setRemainderQty(remainder);
        po.setCartonPlannedQuantities(List.copyOf(plannedQuantities));
        po.setExFtyDate(request.exFtyDate());
        po.setStatus(PO_NOT_STARTED);
        po.setUpdatedBy(RequestActor.current());
        po.setUpdatedAt(LocalDateTime.now());

        LululemonPo saved = poRepository.save(po);

        // Remove every derived record created from the old source values.
        itemRepository.deleteByPoId(poId);
        cartonRepository.deleteByPoId(poId);
        scanEventRepository.deleteByPoId(poId);

        // Preserve lazy generation for untouched POs, but if the user had already generated
        // children, rebuild them now so Carton/Item screens immediately reflect the edit.
        if (hadGeneratedCartons) {
            List<LululemonCarton> regenerated = ensureCartonsGenerated(saved);
            if (hadGeneratedItems) {
                regenerated.forEach(this::ensureItemsGenerated);
            }
        }

        audit("LULULEMON_PO_UPDATE", poId,
                "Updated imported PO " + saved.getPoNumber()
                        + ". ALL_BP formulas were recalculated and generated Carton/Item data was refreshed.");
        socketPublisher.cartonLoadingChanged("LULULEMON_PO_UPDATED", poId);
        return saved;
    }

    /**
     * Delete an imported PO source record. Generated Cartons/Items are cascaded only
     * while the PO has no operational activity.
     */
    public synchronized void deleteImportedPo(String buyerCode, String orderId, String poId) {
        requireOrder(buyerCode, orderId);
        LululemonPo po = requirePo(orderId, poId);
        factoryAccess.assertFactoryAccess(po.getFactoryCode());
        assertImportedPoMutable(po);

        String poNumber = po.getPoNumber();
        itemRepository.deleteByPoId(poId);
        cartonRepository.deleteByPoId(poId);
        scanEventRepository.deleteByPoId(poId);
        poRepository.delete(po);

        audit("LULULEMON_PO_DELETE", poId, "Deleted imported PO " + poNumber + " and its unstarted generated Cartons/Items.");
        socketPublisher.cartonLoadingChanged("LULULEMON_PO_DELETED", poId);
    }

    public List<LululemonCartonItem> listItems(String buyerCode, String orderId, String cartonId) {
        requireOrder(buyerCode, orderId);
        LululemonCarton carton = requireCarton(orderId, cartonId);
        factoryAccess.assertFactoryAccess(carton.getFactoryCode());
        ensureItemsGenerated(carton);
        return itemRepository.findByCartonIdOrderByItemNoAsc(cartonId);
    }

    /** Generate Cartons only when the PO is actually opened. Safe to call repeatedly. */
    public synchronized List<LululemonCarton> ensureCartonsForPo(String buyerCode, String orderId, String poId) {
        requireOrder(buyerCode, orderId);
        LululemonPo po = requirePo(orderId, poId);
        factoryAccess.assertFactoryAccess(po.getFactoryCode());
        return ensureCartonsGenerated(po);
    }

    /** Generate Item slots only when the Carton is actually opened. Safe to call repeatedly. */
    public synchronized List<LululemonCartonItem> ensureItemsForCarton(String buyerCode, String orderId, String cartonId) {
        requireOrder(buyerCode, orderId);
        LululemonCarton carton = requireCarton(orderId, cartonId);
        factoryAccess.assertFactoryAccess(carton.getFactoryCode());
        ensureItemsGenerated(carton);
        return itemRepository.findByCartonIdOrderByItemNoAsc(cartonId);
    }

    public LululemonCarton startCarton(String buyerCode, String orderId, String cartonId) {
        requireOrder(buyerCode, orderId);
        LululemonCarton carton = requireCarton(orderId, cartonId);
        factoryAccess.assertFactoryAccess(carton.getFactoryCode());
        ensureItemsGenerated(carton);
        if (CARTON_FINISHED.equals(carton.getStatus()) || CARTON_LABEL_CONFIRMED.equals(carton.getStatus()) || CARTON_READY_TO_SHIP.equals(carton.getStatus())) {
            throw new IllegalArgumentException("Carton " + carton.getCartonNo() + " is already finished and cannot be started again.");
        }
        if (carton.getStartedAt() == null) {
            carton.setStartedAt(LocalDateTime.now());
            carton.setStartedBy(RequestActor.current());
        }
        carton.setStatus(CARTON_PACKING);
        carton.setUpdatedAt(LocalDateTime.now());
        carton.setUpdatedBy(RequestActor.current());
        return cartonRepository.save(carton);
    }

    public synchronized LululemonItemScanResponse scanItem(String buyerCode, String orderId, String cartonId, LululemonItemScanRequest request) {
        requireOrder(buyerCode, orderId);
        String sku = normalizeSku(request == null ? null : request.sku());
        if (sku == null) throw new IllegalArgumentException("SKU barcode is required.");

        LululemonCarton carton = requireCarton(orderId, cartonId);
        factoryAccess.assertFactoryAccess(carton.getFactoryCode());
        ensureItemsGenerated(carton);
        if (CARTON_FINISHED.equals(carton.getStatus()) || CARTON_LABEL_CONFIRMED.equals(carton.getStatus()) || CARTON_READY_TO_SHIP.equals(carton.getStatus())) {
            rejectScan(orderId, carton.getPoId(), cartonId, "ITEM", sku, "Carton is already finished");
            throw new IllegalArgumentException("Carton is already finished. Reopen/correct it before scanning more products.");
        }

        LululemonPo po = requirePo(orderId, carton.getPoId());
        boolean assignedNow = false;
        if (clean(po.getSku()) == null) {
            assertSkuAvailableForPo(orderId, po.getId(), sku);
            Query query = Query.query(Criteria.where("_id").is(po.getId()).andOperator(
                    new Criteria().orOperator(Criteria.where("sku").is(null), Criteria.where("sku").is(""))));
            Update update = new Update()
                    .set("sku", sku)
                    .set("skuAssignedBy", RequestActor.current())
                    .set("skuAssignedAt", LocalDateTime.now())
                    .set("updatedBy", RequestActor.current())
                    .set("updatedAt", LocalDateTime.now());
            LululemonPo claimed = mongoTemplate.findAndModify(query, update,
                    FindAndModifyOptions.options().returnNew(true), LululemonPo.class);
            if (claimed != null) {
                po = claimed;
                assignedNow = true;
            } else {
                po = requirePo(orderId, po.getId());
            }
        }

        if (!sku.equals(normalizeSku(po.getSku()))) {
            rejectScan(orderId, po.getId(), cartonId, "ITEM", sku,
                    "Wrong SKU. This PO is locked to SKU " + po.getSku());
            throw new IllegalArgumentException("Wrong SKU. PO " + po.getPoNumber() + " uses SKU " + po.getSku() + ".");
        }

        Query nextItem = Query.query(Criteria.where("cartonId").is(cartonId).and("status").is(ITEM_PENDING))
                .with(Sort.by(Sort.Direction.ASC, "itemNo"));
        Update scanUpdate = new Update()
                .set("status", ITEM_PASS)
                .set("scannedSku", sku)
                .set("scannedBy", RequestActor.current())
                .set("scannedAt", LocalDateTime.now());
        LululemonCartonItem item = mongoTemplate.findAndModify(nextItem, scanUpdate,
                FindAndModifyOptions.options().returnNew(true), LululemonCartonItem.class);
        if (item == null) {
            rejectScan(orderId, po.getId(), cartonId, "ITEM", sku, "Carton already contains the planned quantity");
            throw new IllegalArgumentException("Carton already has " + carton.getPlannedQty() + " accepted product scans.");
        }

        int scannedQty = Math.toIntExact(itemRepository.countByCartonIdAndStatus(cartonId, ITEM_PASS));
        carton.setScannedQty(scannedQty);
        carton.setStatus(CARTON_PACKING);
        if (carton.getStartedAt() == null) {
            carton.setStartedAt(LocalDateTime.now());
            carton.setStartedBy(RequestActor.current());
        }
        carton.setUpdatedBy(RequestActor.current());
        carton.setUpdatedAt(LocalDateTime.now());
        carton = cartonRepository.save(carton);

        if (!PO_PACKING.equals(po.getStatus())) {
            po.setStatus(PO_PACKING);
            po.setUpdatedAt(LocalDateTime.now());
            po.setUpdatedBy(RequestActor.current());
            po = poRepository.save(po);
        }

        passScan(orderId, po.getId(), cartonId, "ITEM", sku,
                "Accepted item " + item.getItemNo() + "/" + carton.getPlannedQty());
        socketPublisher.cartonLoadingChanged("LULULEMON_ITEM_SCANNED", cartonId);
        return new LululemonItemScanResponse(po, carton, item, assignedNow,
                assignedNow ? "First SKU assigned to PO. All remaining products in this PO must use the same SKU." : "SKU accepted.");
    }

    public synchronized LululemonCarton undoLastItem(String buyerCode, String orderId, String cartonId) {
        requireOrder(buyerCode, orderId);
        LululemonCarton carton = requireCarton(orderId, cartonId);
        factoryAccess.assertFactoryAccess(carton.getFactoryCode());
        if (CARTON_FINISHED.equals(carton.getStatus()) || CARTON_LABEL_CONFIRMED.equals(carton.getStatus()) || CARTON_READY_TO_SHIP.equals(carton.getStatus())) {
            throw new IllegalArgumentException("Finished cartons cannot be changed with Undo.");
        }
        LululemonCartonItem item = itemRepository.findFirstByCartonIdAndStatusOrderByItemNoDesc(cartonId, ITEM_PASS)
                .orElseThrow(() -> new IllegalArgumentException("This carton has no accepted scan to undo."));
        item.setStatus(ITEM_PENDING);
        item.setScannedSku(null);
        item.setScannedBy(null);
        item.setScannedAt(null);
        itemRepository.save(item);

        int scannedQty = Math.toIntExact(itemRepository.countByCartonIdAndStatus(cartonId, ITEM_PASS));
        carton.setScannedQty(scannedQty);
        carton.setStatus(scannedQty == 0 ? CARTON_READY : CARTON_PACKING);
        carton.setUpdatedAt(LocalDateTime.now());
        carton.setUpdatedBy(RequestActor.current());
        cartonRepository.save(carton);

        LululemonPo po = requirePo(orderId, carton.getPoId());
        long poScans = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId()).stream()
                .mapToLong(value -> value.getScannedQty() == null ? 0 : value.getScannedQty())
                .sum();
        if (poScans == 0 && cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId()).stream().noneMatch(c -> c.getSscc() != null)) {
            po.setSku(null);
            po.setSkuAssignedAt(null);
            po.setSkuAssignedBy(null);
            po.setStatus(PO_NOT_STARTED);
            po.setUpdatedAt(LocalDateTime.now());
            po.setUpdatedBy(RequestActor.current());
            poRepository.save(po);
        }
        audit("LULULEMON_UNDO_ITEM", cartonId, "Undid the last accepted product scan for carton " + carton.getCartonNo());
        socketPublisher.cartonLoadingChanged("LULULEMON_ITEM_UNDONE", cartonId);
        return carton;
    }

    public synchronized LululemonCarton finishCarton(String buyerCode, String orderId, String cartonId) {
        requireOrder(buyerCode, orderId);
        LululemonCarton carton = requireCarton(orderId, cartonId);
        factoryAccess.assertFactoryAccess(carton.getFactoryCode());
        if (CARTON_READY_TO_SHIP.equals(carton.getStatus())) return carton;
        int scanned = Math.toIntExact(itemRepository.countByCartonIdAndStatus(cartonId, ITEM_PASS));
        if (scanned != safeInt(carton.getPlannedQty())) {
            throw new IllegalArgumentException("Carton cannot be finished. Scanned " + scanned + " / " + carton.getPlannedQty() + " item(s).");
        }
        LululemonPo po = requirePo(orderId, carton.getPoId());
        if (normalizeSku(po.getSku()) == null) throw new IllegalArgumentException("PO SKU has not been assigned yet.");
        carton.setScannedQty(scanned);
        carton.setStatus(CARTON_FINISHED);
        carton.setFinishedBy(RequestActor.current());
        carton.setFinishedAt(LocalDateTime.now());
        carton.setUpdatedBy(RequestActor.current());
        carton.setUpdatedAt(LocalDateTime.now());
        carton = cartonRepository.save(carton);
        refreshPoStatus(po);
        audit("LULULEMON_FINISH_CARTON", cartonId,
                "Finished PO " + po.getPoNumber() + " carton " + carton.getCartonNo() + " with " + scanned + " item(s)");
        socketPublisher.cartonLoadingChanged("LULULEMON_CARTON_FINISHED", cartonId);
        return carton;
    }

    public LululemonImportResult importShippingList(String buyerCode, String orderId, MultipartFile file) {
        requireOrder(buyerCode, orderId);
        int updated = 0;
        List<String> warnings = new ArrayList<>();
        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = findShippingSheet(workbook);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            int headerRowIndex = findShippingHeaderRow(sheet, evaluator);
            Row header = sheet.getRow(headerRowIndex);
            Map<String, Integer> columns = headerColumns(header, evaluator);
            int poCol = requiredColumn(columns, "PO", "PONO", "PURCHASEORDER");
            int exFtyCol = requiredColumn(columns, "EXFTYDATE", "EXFACTORYDATE", "EXFTY", "EXFACTORY");

            for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                String poNumber = clean(excelSupport.text(row, poCol, evaluator));
                if (poNumber == null) continue;
                LocalDate exFty = excelSupport.localDate(row, exFtyCol, evaluator);
                if (exFty == null) {
                    warnings.add("PO " + poNumber + " has no Ex-fty Date at row " + (rowIndex + 1));
                    continue;
                }
                Optional<LululemonPo> poOpt = poRepository.findByOrderIdAndBuyerCodeAndPoNumber(orderId, BUYER, poNumber);
                if (poOpt.isEmpty()) {
                    warnings.add("PO " + poNumber + " does not exist in All_BP and was skipped.");
                    continue;
                }
                LululemonPo po = poOpt.get();
                po.setExFtyDate(exFty);
                po.setUpdatedAt(LocalDateTime.now());
                po.setUpdatedBy(RequestActor.current());
                refreshPoStatus(po);
                updated++;
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot import LULULEMON Shipping List: " + cleanMessage(ex));
        }
        audit("LULULEMON_SHIPPING_IMPORT", orderId, "Updated Ex-fty Date for " + updated + " PO(s)");
        socketPublisher.cartonLoadingChanged("LULULEMON_EX_FTY_UPDATED", orderId);
        return new LululemonImportResult(true, 0, 0, 0, updated, warnings);
    }

    public LululemonPo updateExFtyDate(String buyerCode, String orderId, LululemonShippingUpdateRequest request) {
        requireOrder(buyerCode, orderId);
        if (request == null || clean(request.poNumber()) == null || request.exFtyDate() == null) {
            throw new IllegalArgumentException("PO number and Ex-fty Date are required.");
        }
        LululemonPo po = poRepository.findByOrderIdAndBuyerCodeAndPoNumber(orderId, BUYER, clean(request.poNumber()))
                .orElseThrow(() -> new IllegalArgumentException("PO " + request.poNumber() + " does not exist in All_BP."));
        po.setExFtyDate(request.exFtyDate());
        po.setUpdatedAt(LocalDateTime.now());
        po.setUpdatedBy(RequestActor.current());
        refreshPoStatus(po);
        return po;
    }

    public LululemonLabelLookupResponse lookupCartonLabel(String buyerCode, String orderId, LululemonLabelLookupRequest request) {
        requireOrder(buyerCode, orderId);
        String sku = normalizeSku(request == null ? null : request.sku());
        if (sku == null) throw new IllegalArgumentException("Scan the SKU barcode on the Carton Loading label first.");

        List<Candidate> candidates = new ArrayList<>();
        for (LululemonPo po : poRepository.findByOrderIdAndBuyerCodeAndSku(orderId, BUYER, sku)) {
            if (!factoryAccess.canAccessFactory(po.getFactoryCode())) continue;
            if (po.getExFtyDate() == null) continue;
            List<LululemonCarton> pending = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId()).stream()
                    .filter(c -> (CARTON_FINISHED.equals(c.getStatus()) || CARTON_LABEL_CONFIRMED.equals(c.getStatus())) && clean(c.getSscc()) == null)
                    .toList();
            if (!pending.isEmpty()) candidates.add(new Candidate(po, pending));
        }
        if (candidates.isEmpty()) {
            rejectScan(orderId, null, null, "CARTON_LABEL_SKU", sku,
                    "No released finished carton is waiting for this SKU");
            throw new IllegalArgumentException("No finished carton with SKU " + sku + " is waiting for SSCC assignment. Check Ex-fty Date and Packing status.");
        }
        if (candidates.size() > 1) {
            String pos = candidates.stream().map(c -> c.po().getPoNumber()).distinct().collect(Collectors.joining(", "));
            rejectScan(orderId, null, null, "CARTON_LABEL_SKU", sku, "SKU matches more than one active PO: " + pos);
            throw new IllegalArgumentException("SKU " + sku + " matches more than one released PO (" + pos + "). Resolve the duplicate before assigning SSCC.");
        }
        Candidate candidate = candidates.get(0);
        String cartonText = candidate.cartons().size() == 1
                ? "carton " + candidate.cartons().get(0).getCartonNo()
                : candidate.cartons().size() + " pending cartons";
        passScan(orderId, candidate.po().getId(), candidate.cartons().size() == 1 ? candidate.cartons().get(0).getId() : null,
                "CARTON_LABEL_SKU", sku, "Located " + cartonText);
        return new LululemonLabelLookupResponse(candidate.po(), candidate.cartons(),
                "Enter the Unit Qty shown on the physical Carton Loading label to narrow the pending cartons. If several cartons have the same SKU + Qty, use the oldest finished carton first (FIFO), then Confirm before scanning SSCC-18.");
    }

    public synchronized LululemonCarton confirmCartonLabel(String buyerCode, String orderId, String cartonId, LululemonConfirmLabelRequest request) {
        requireOrder(buyerCode, orderId);
        LululemonCarton carton = requireCarton(orderId, cartonId);
        factoryAccess.assertFactoryAccess(carton.getFactoryCode());
        if (!CARTON_FINISHED.equals(carton.getStatus()) && !CARTON_LABEL_CONFIRMED.equals(carton.getStatus())) {
            throw new IllegalArgumentException("Only a finished carton can be confirmed against a Carton Loading label.");
        }
        LululemonPo po = requirePo(orderId, carton.getPoId());
        String scannedSku = normalizeSku(request == null ? null : request.sku());
        if (scannedSku == null || !scannedSku.equals(normalizeSku(po.getSku()))) {
            rejectScan(orderId, po.getId(), cartonId, "CARTON_LABEL_SKU", scannedSku,
                    "Carton label SKU does not match PO SKU");
            throw new IllegalArgumentException("Carton Loading label SKU does not match PO " + po.getPoNumber() + ".");
        }
        if (carton.getScannedQty() == null || !carton.getScannedQty().equals(carton.getPlannedQty())) {
            throw new IllegalArgumentException("System quantity is not complete for this carton.");
        }
        carton.setLabelConfirmedSku(scannedSku);
        carton.setLabelConfirmedBy(RequestActor.current());
        carton.setLabelConfirmedAt(LocalDateTime.now());
        carton.setStatus(CARTON_LABEL_CONFIRMED);
        carton.setUpdatedBy(RequestActor.current());
        carton.setUpdatedAt(LocalDateTime.now());
        carton = cartonRepository.save(carton);
        refreshPoStatus(po);
        audit("LULULEMON_CONFIRM_CARTON_LABEL", cartonId,
                "Confirmed physical Carton Loading label for PO " + po.getPoNumber() + " carton " + carton.getCartonNo()
                        + ", system Qty " + carton.getScannedQty());
        socketPublisher.cartonLoadingChanged("LULULEMON_LABEL_CONFIRMED", cartonId);
        return carton;
    }

    public synchronized LululemonCarton assignSscc(String buyerCode, String orderId, String cartonId, LululemonAssignSsccRequest request) {
        requireOrder(buyerCode, orderId);
        LululemonCarton carton = requireCarton(orderId, cartonId);
        factoryAccess.assertFactoryAccess(carton.getFactoryCode());
        String sscc = normalizeSscc(request == null ? null : request.sscc());
        if (sscc == null) throw new IllegalArgumentException("SSCC-18 barcode is required.");
        if (!CARTON_LABEL_CONFIRMED.equals(carton.getStatus())) {
            rejectScan(orderId, carton.getPoId(), cartonId, "SSCC", sscc, "Carton label has not been confirmed");
            throw new IllegalArgumentException("Confirm SKU and physical carton information before scanning SSCC-18.");
        }
        if (clean(carton.getSscc()) != null) {
            throw new IllegalArgumentException("This carton already has SSCC " + carton.getSscc() + ".");
        }
        if (cartonRepository.existsBySscc18(sscc)) {
            rejectScan(orderId, carton.getPoId(), cartonId, "SSCC", sscc, "SSCC is already assigned");
            throw new IllegalArgumentException("SSCC " + sscc + " has already been assigned to another carton.");
        }
        carton.setSscc(sscc);
        carton.setSsccAssignedBy(RequestActor.current());
        carton.setSsccAssignedAt(LocalDateTime.now());
        carton.setStatus(CARTON_READY_TO_SHIP);
        carton.setUpdatedBy(RequestActor.current());
        carton.setUpdatedAt(LocalDateTime.now());
        carton = cartonRepository.save(carton);
        LululemonPo po = requirePo(orderId, carton.getPoId());
        refreshPoStatus(po);
        passScan(orderId, po.getId(), cartonId, "SSCC", sscc, "SSCC assigned successfully");
        audit("LULULEMON_ASSIGN_SSCC", cartonId,
                "Assigned SSCC " + sscc + " to PO " + po.getPoNumber() + " carton " + carton.getCartonNo());
        socketPublisher.cartonLoadingChanged("LULULEMON_SSCC_ASSIGNED", cartonId);
        return carton;
    }

    public synchronized LululemonPo resetPoSku(String buyerCode, String orderId, String poId, LululemonExceptionRequest request) {
        requireOrder(buyerCode, orderId);
        String reason = requireReason(request);
        LululemonPo po = requirePo(orderId, poId);
        factoryAccess.assertFactoryAccess(po.getFactoryCode());

        List<LululemonCarton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(poId);
        boolean finalized = cartons.stream().anyMatch(c ->
                CARTON_FINISHED.equals(c.getStatus())
                        || CARTON_LABEL_CONFIRMED.equals(c.getStatus())
                        || CARTON_READY_TO_SHIP.equals(c.getStatus())
                        || clean(c.getSscc()) != null);
        if (finalized) {
            throw new IllegalArgumentException("Re-open finished cartons and unassign SSCC before resetting the PO SKU.");
        }

        String oldSku = po.getSku();
        List<LululemonCartonItem> items = itemRepository.findByPoIdOrderByCartonNoAscItemNoAsc(poId);
        for (LululemonCartonItem item : items) {
            if (ITEM_PASS.equals(item.getStatus()) || clean(item.getScannedSku()) != null) {
                item.setStatus(ITEM_PENDING);
                item.setScannedSku(null);
                item.setScannedBy(null);
                item.setScannedAt(null);
                item.setUpdatedAt(LocalDateTime.now());
            }
        }
        if (!items.isEmpty()) itemRepository.saveAll(items);

        for (LululemonCarton carton : cartons) {
            carton.setScannedQty(0);
            carton.setStatus(CARTON_READY);
            carton.setStartedAt(null);
            carton.setStartedBy(null);
            carton.setUpdatedAt(LocalDateTime.now());
            carton.setUpdatedBy(RequestActor.current());
        }
        if (!cartons.isEmpty()) cartonRepository.saveAll(cartons);

        po.setSku(null);
        po.setSkuAssignedAt(null);
        po.setSkuAssignedBy(null);
        po.setStatus(PO_NOT_STARTED);
        po.setUpdatedAt(LocalDateTime.now());
        po.setUpdatedBy(RequestActor.current());
        po = poRepository.save(po);

        audit("LULULEMON_RESET_PO_SKU", poId,
                "Reset PO SKU from " + safe(oldSku) + " for PO " + po.getPoNumber() + ". Reason: " + reason);
        socketPublisher.cartonLoadingChanged("LULULEMON_PO_SKU_RESET", poId);
        return po;
    }

    public synchronized LululemonCarton reopenCarton(String buyerCode, String orderId, String cartonId, LululemonExceptionRequest request) {
        requireOrder(buyerCode, orderId);
        String reason = requireReason(request);
        LululemonCarton carton = requireCarton(orderId, cartonId);
        factoryAccess.assertFactoryAccess(carton.getFactoryCode());
        if (clean(carton.getSscc()) != null) {
            throw new IllegalArgumentException("Unassign SSCC before re-opening this carton.");
        }
        if (clean(carton.getWeightStatus()) != null) {
            throw new IllegalArgumentException("This carton already has a weighing result. Re-open/resolve Weighing before re-opening the carton.");
        }
        if (!CARTON_FINISHED.equals(carton.getStatus()) && !CARTON_LABEL_CONFIRMED.equals(carton.getStatus())) {
            throw new IllegalArgumentException("Only a Finished or Label Confirmed carton can be re-opened.");
        }

        String oldStatus = carton.getStatus();
        carton.setStatus(safeInt(carton.getScannedQty()) > 0 ? CARTON_PACKING : CARTON_READY);
        carton.setFinishedAt(null);
        carton.setFinishedBy(null);
        carton.setLabelConfirmedAt(null);
        carton.setLabelConfirmedBy(null);
        carton.setLabelConfirmedSku(null);
        carton.setUpdatedAt(LocalDateTime.now());
        carton.setUpdatedBy(RequestActor.current());
        carton = cartonRepository.save(carton);

        LululemonPo po = requirePo(orderId, carton.getPoId());
        refreshPoStatus(po);
        audit("LULULEMON_REOPEN_CARTON", cartonId,
                "Re-opened PO " + po.getPoNumber() + " carton " + carton.getCartonNo()
                        + " from " + oldStatus + ". Reason: " + reason);
        socketPublisher.cartonLoadingChanged("LULULEMON_CARTON_REOPENED", cartonId);
        return carton;
    }

    public synchronized LululemonCarton unassignSscc(String buyerCode, String orderId, String cartonId, LululemonExceptionRequest request) {
        requireOrder(buyerCode, orderId);
        String reason = requireReason(request);
        LululemonCarton carton = requireCarton(orderId, cartonId);
        factoryAccess.assertFactoryAccess(carton.getFactoryCode());
        String oldSscc = clean(carton.getSscc());
        if (oldSscc == null) throw new IllegalArgumentException("This carton does not have an assigned SSCC.");
        if (clean(carton.getWeightStatus()) != null) {
            throw new IllegalArgumentException("This carton already has a weighing result. Re-open/resolve Weighing before unassigning SSCC.");
        }

        carton.setSscc(null);
        carton.setSsccAssignedAt(null);
        carton.setSsccAssignedBy(null);
        carton.setStatus(carton.getLabelConfirmedAt() == null ? CARTON_FINISHED : CARTON_LABEL_CONFIRMED);
        carton.setUpdatedAt(LocalDateTime.now());
        carton.setUpdatedBy(RequestActor.current());
        carton = cartonRepository.save(carton);

        LululemonPo po = requirePo(orderId, carton.getPoId());
        refreshPoStatus(po);
        audit("LULULEMON_UNASSIGN_SSCC", cartonId,
                "Unassigned SSCC " + oldSscc + " from PO " + po.getPoNumber() + " carton " + carton.getCartonNo()
                        + ". Reason: " + reason);
        socketPublisher.cartonLoadingChanged("LULULEMON_SSCC_UNASSIGNED", cartonId);
        return carton;
    }

    public List<LululemonTraceResult> trace(String buyerCode, String orderId, String keyword) {
        requireOrder(buyerCode, orderId);
        String raw = clean(keyword);
        if (raw == null) throw new IllegalArgumentException("PO, SKU, Carton ID or SSCC is required.");
        String skuKey = normalizeSku(raw);
        LinkedHashMap<String, LululemonCarton> matchedCartons = new LinkedHashMap<>();
        LinkedHashMap<String, LululemonPo> matchedPos = new LinkedHashMap<>();

        cartonRepository.findBySscc18(raw).ifPresent(carton -> {
            if (orderId.equals(carton.getOrderId()) && BUYER.equals(carton.getBuyerCode()) && factoryAccess.canAccessFactory(carton.getFactoryCode())) {
                matchedCartons.put(carton.getId(), carton);
            }
        });
        cartonRepository.findById(raw).ifPresent(carton -> {
            if (orderId.equals(carton.getOrderId()) && BUYER.equals(carton.getBuyerCode()) && factoryAccess.canAccessFactory(carton.getFactoryCode())) {
                matchedCartons.put(carton.getId(), carton);
            }
        });

        String lowered = raw.toLowerCase(Locale.ROOT);
        for (LululemonPo po : poRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, BUYER)) {
            if (!factoryAccess.canAccessFactory(po.getFactoryCode())) continue;
            boolean poMatch = safe(po.getPoNumber()).toLowerCase(Locale.ROOT).contains(lowered)
                    || safe(po.getMasterPo()).toLowerCase(Locale.ROOT).contains(lowered)
                    || (skuKey != null && skuKey.equals(normalizeSku(po.getSku())));
            if (poMatch) matchedPos.put(po.getId(), po);
        }

        for (LululemonCarton carton : matchedCartons.values()) {
            LululemonPo po = requirePo(orderId, carton.getPoId());
            matchedPos.put(po.getId(), po);
        }

        List<LululemonTraceResult> results = new ArrayList<>();
        if (!matchedCartons.isEmpty()) {
            for (LululemonCarton carton : matchedCartons.values()) {
                LululemonPo po = requirePo(orderId, carton.getPoId());
                List<LululemonCartonItem> items = itemRepository.findByCartonIdOrderByItemNoAsc(carton.getId());
                List<LululemonScanEvent> events = scanEventRepository.findTop200ByCartonIdOrderByCreatedAtDesc(carton.getId());
                results.add(new LululemonTraceResult(po, carton, items, events, weightEventsForCarton(carton.getId())));
            }
            return results;
        }

        for (LululemonPo po : matchedPos.values()) {
            List<LululemonCarton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId());
            if (cartons.isEmpty()) {
                results.add(new LululemonTraceResult(po, null, List.of(), scanEventRepository.findTop200ByPoIdOrderByCreatedAtDesc(po.getId()), weightEventsForPo(po.getId())));
                continue;
            }
            for (LululemonCarton carton : cartons) {
                results.add(new LululemonTraceResult(
                        po, carton, itemRepository.findByCartonIdOrderByItemNoAsc(carton.getId()),
                        scanEventRepository.findTop200ByCartonIdOrderByCreatedAtDesc(carton.getId()),
                        weightEventsForCarton(carton.getId())
                ));
            }
        }
        return results.stream().limit(200).toList();
    }

    private List<LululemonWeightEvent> weightEventsForCarton(String cartonId) {
        if (clean(cartonId) == null) return List.of();
        Query query = Query.query(Criteria.where("cartonId").is(cartonId))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(200);
        return mongoTemplate.find(query, LululemonWeightEvent.class);
    }

    private List<LululemonWeightEvent> weightEventsForPo(String poId) {
        if (clean(poId) == null) return List.of();
        Query query = Query.query(Criteria.where("poId").is(poId))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(200);
        return mongoTemplate.find(query, LululemonWeightEvent.class);
    }

    public List<LululemonScanEvent> recentEvents(String buyerCode, String orderId) {
        requireOrder(buyerCode, orderId);
        Set<String> allowedFactories = new HashSet<>(factoryAccess.accessibleFactories());
        boolean unrestricted = factoryAccess.isUnrestricted();
        Map<String, String> poFactory = poRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, BUYER).stream()
                .collect(Collectors.toMap(LululemonPo::getId, LululemonPo::getFactoryCode, (a, b) -> a));
        return scanEventRepository.findTop100ByOrderIdAndBuyerCodeOrderByCreatedAtDesc(orderId, BUYER).stream()
                .filter(event -> unrestricted || event.getPoId() == null || allowedFactories.contains(normalizeFactory(poFactory.get(event.getPoId()))))
                .toList();
    }

    private LululemonPoSummary summary(LululemonPo po) {
        List<LululemonCarton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId());
        int finished = (int) cartons.stream().filter(c -> CARTON_FINISHED.equals(c.getStatus()) || CARTON_LABEL_CONFIRMED.equals(c.getStatus()) || CARTON_READY_TO_SHIP.equals(c.getStatus())).count();
        int assigned = (int) cartons.stream().filter(c -> CARTON_READY_TO_SHIP.equals(c.getStatus()) && clean(c.getSscc()) != null).count();
        int scanned = cartons.stream().mapToInt(c -> safeInt(c.getScannedQty())).sum();
        return new LululemonPoSummary(po, finished, assigned, scanned);
    }

    private void refreshPoStatus(LululemonPo po) {
        List<LululemonCarton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId());
        if (cartons.isEmpty()) {
            po.setStatus(PO_NOT_STARTED);
        } else if (cartons.stream().allMatch(c -> CARTON_READY_TO_SHIP.equals(c.getStatus()) && clean(c.getSscc()) != null)) {
            po.setStatus(PO_READY_TO_SHIP);
        } else if (cartons.stream().allMatch(c ->
                CARTON_FINISHED.equals(c.getStatus())
                        || CARTON_LABEL_CONFIRMED.equals(c.getStatus())
                        || CARTON_READY_TO_SHIP.equals(c.getStatus()))) {
            if (po.getExFtyDate() == null) {
                po.setStatus(PO_WAITING_EX_FTY);
            } else if (cartons.stream().anyMatch(c -> CARTON_LABEL_CONFIRMED.equals(c.getStatus()) || clean(c.getSscc()) != null)) {
                po.setStatus(PO_WAITING_SSCC);
            } else {
                po.setStatus(PO_WAITING_LABEL);
            }
        } else if (cartons.stream().anyMatch(c -> safeInt(c.getScannedQty()) > 0 || CARTON_PACKING.equals(c.getStatus()))) {
            po.setStatus(PO_PACKING);
        } else {
            po.setStatus(PO_NOT_STARTED);
        }
        po.setUpdatedAt(LocalDateTime.now());
        po.setUpdatedBy(RequestActor.current());
        poRepository.save(po);
    }

    private List<LululemonCarton> ensureCartonsGenerated(LululemonPo po) {
        List<LululemonCarton> existing = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId());
        if (!existing.isEmpty()) return existing;

        int totalQty = safeInt(po.getTotalQty());
        int qtyPerCarton = safeInt(po.getQtyPerCarton());
        if (totalQty <= 0 || qtyPerCarton <= 0) {
            throw new IllegalArgumentException("PO " + po.getPoNumber() + " does not contain valid Total Qty / Pcs per Carton planning data.");
        }

        LocalDateTime now = LocalDateTime.now();
        String actor = RequestActor.current();
        List<Integer> quantities = po.getCartonPlannedQuantities() == null || po.getCartonPlannedQuantities().isEmpty()
                ? cartonQuantities(totalQty, qtyPerCarton)
                : List.copyOf(po.getCartonPlannedQuantities());
        List<LululemonCarton> batch = new ArrayList<>(quantities.size());
        for (int index = 0; index < quantities.size(); index++) {
            LululemonCarton carton = new LululemonCarton();
            carton.setBuyerCode(BUYER);
            carton.setOrderId(po.getOrderId());
            carton.setPoId(po.getId());
            carton.setPoNumber(po.getPoNumber());
            carton.setFactoryCode(po.getFactoryCode());
            carton.setCartonNo(index + 1);
            carton.setPlannedQty(quantities.get(index));
            carton.setScannedQty(0);
            carton.setStatus(CARTON_READY);
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

    private void ensureItemsGenerated(LululemonCarton carton) {
        if (itemRepository.existsByCartonId(carton.getId())) return;
        int plannedQty = safeInt(carton.getPlannedQty());
        if (plannedQty <= 0) return;

        LocalDateTime now = LocalDateTime.now();
        List<LululemonCartonItem> batch = new ArrayList<>(plannedQty);
        for (int itemNo = 1; itemNo <= plannedQty; itemNo++) {
            LululemonCartonItem item = new LululemonCartonItem();
            item.setBuyerCode(BUYER);
            item.setOrderId(carton.getOrderId());
            item.setPoId(carton.getPoId());
            item.setPoNumber(carton.getPoNumber());
            item.setCartonId(carton.getId());
            item.setCartonNo(carton.getCartonNo());
            item.setItemNo(itemNo);
            item.setStatus(ITEM_PENDING);
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

    private PackingOrder requireOrder(String buyerCode, String orderId) {
        String buyer = BuyerAccess.normalize(buyerCode);
        if (!BUYER.equals(buyer)) throw new IllegalArgumentException("This workflow is available only for LULULEMON.");
        return orderService.getEntity(BUYER, orderId);
    }

    private LululemonPo requirePo(String orderId, String poId) {
        return poRepository.findByIdAndOrderIdAndBuyerCode(poId, orderId, BUYER)
                .orElseThrow(() -> new IllegalArgumentException("LULULEMON PO not found."));
    }

    private LululemonCarton requireCarton(String orderId, String cartonId) {
        return cartonRepository.findByIdAndOrderIdAndBuyerCode(cartonId, orderId, BUYER)
                .orElseThrow(() -> new IllegalArgumentException("LULULEMON carton not found."));
    }

    private void assertImportedPoMutable(LululemonPo po) {
        List<LululemonCarton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId());
        boolean operationalActivity = clean(po.getSku()) != null || cartons.stream().anyMatch(carton ->
                safeInt(carton.getScannedQty()) > 0
                        || clean(carton.getSscc()) != null
                        || carton.getStartedAt() != null
                        || carton.getFinishedAt() != null
                        || carton.getLabelConfirmedAt() != null
                        || carton.getSsccAssignedAt() != null
                        || (clean(carton.getStatus()) != null && !CARTON_READY.equals(carton.getStatus()))
        );
        if (operationalActivity) {
            throw new IllegalArgumentException("PO cannot be edited or deleted after Packing/Carton Loading has started.");
        }

        Query handoffQuery = Query.query(Criteria.where("buyerCode").is(BUYER)
                .and("orderId").is(po.getOrderId())
                .and("status").is("SENT")
                .and("pos.poId").is(po.getId()));
        if (mongoTemplate.exists(handoffQuery, LululemonPrintRequest.class)) {
            throw new IllegalArgumentException("PO is already in an active Print Room handoff. Cancel the handoff before editing or deleting this PO.");
        }
    }

    private void assertReplaceAllowed(String orderId) {
        List<LululemonPo> existingPos = poRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, BUYER);
        if (existingPos.isEmpty()) return;
        boolean started = existingPos.stream().anyMatch(po -> clean(po.getSku()) != null)
                || cartonRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAscCartonNoAsc(orderId, BUYER).stream()
                .anyMatch(carton -> safeInt(carton.getScannedQty()) > 0 || clean(carton.getSscc()) != null
                        || !CARTON_READY.equals(carton.getStatus()));
        if (started) {
            throw new IllegalArgumentException("All_BP cannot be replaced after Packing has started. Create a new Order or correct the operational data first.");
        }
    }

    private int findHeaderRow(Sheet sheet, FormulaEvaluator evaluator) {
        int limit = Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 12);
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= limit; rowIndex++) {
            Map<String, Integer> columns = resolveAllBpColumns(sheet.getRow(rowIndex), evaluator);
            if (columns.containsKey("PO") && columns.containsKey("STYLE")
                    && columns.containsKey("QTY") && columns.containsKey("PCS_CTN")) {
                return rowIndex;
            }
        }
        throw new IllegalArgumentException("Cannot find the All_BP header row. Required headers include PO, Style, Q'ty and Pcs/ctn.");
    }

    private int findShippingHeaderRow(Sheet sheet, FormulaEvaluator evaluator) {
        int limit = Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 15);
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= limit; rowIndex++) {
            Map<String, Integer> columns = headerColumns(sheet.getRow(rowIndex), evaluator);
            if (optionalColumn(columns, "PO", "PONO", "PURCHASEORDER") != null
                    && optionalColumn(columns, "EXFTYDATE", "EXFACTORYDATE", "EXFTY", "EXFACTORY") != null) {
                return rowIndex;
            }
        }
        throw new IllegalArgumentException("Cannot find Shipping List headers PO and Ex-fty Date.");
    }

    private Sheet findShippingSheet(Workbook workbook) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            String key = TextNormalizer.headerKey(sheet.getSheetName());
            if (key.contains("SHIPPING") || key.contains("SHIPLIST") || key.contains("ALLBP")) return sheet;
        }
        return workbook.getSheetAt(0);
    }

    private static Map<String, Set<String>> createAllBpAliases() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        addAliases(result, "HOD", "HOD", "HANDOVERDATE", "HODHANDOVERDATE");
        addAliases(result, "SGS_TESTING", "SGSTESTING", "SGSTEST");
        addAliases(result, "GB_TESTING", "GBTESTING", "GBTEST");
        addAliases(result, "BV_INSPECTION", "BVINSPECTION", "BVINSPECT");
        addAliases(result, "DC_CODE", "DCCODE", "DC");
        addAliases(result, "DESTINATION", "DESTINATION", "DEST");
        addAliases(result, "CHANNEL", "CHANEL", "CHANNEL");
        addAliases(result, "MASTER_PO", "MASTERPO");
        addAliases(result, "PO", "PO", "PONO", "PONUMBER", "PURCHASEORDER");
        addAliases(result, "PACKING_PLAN", "PACKINGPLAN", "PACKINGPLANNO");
        addAliases(result, "SALES_ORDER_PTS", "SOPTS", "SO", "SALESORDERPTS", "SALESORDER");
        addAliases(result, "STYLE", "STYLE", "STYLENO", "STYLENUMBER");
        addAliases(result, "DESCRIPTION", "DESCRIPTION", "DESC");
        addAliases(result, "COLOR", "COLORDESCRIPTION", "COLOR", "COLOURDESCRIPTION", "COLOUR");
        addAliases(result, "QTY", "QTY", "QTYTOTAL", "TOTALQTY", "QUANTITY", "ORDERQTY");
        addAliases(result, "PCS_CTN", "PCSCTN", "PCSPERCTN", "PCSPERCARTON", "QTYPERCTN", "PCSCTNS", "UNITQTY");
        addAliases(result, "ODD_RATIO", "LE");
        addAliases(result, "CTNS", "CTNS", "CARTONS", "CARTONQTY", "TOTALCARTONS");
        addAliases(result, "REMAINDER", "DU", "REMAINDER", "REMAINDERQTY", "BALANCEQTY", "ODDPCS");
        addAliases(result, "REMARK", "REMARK");
        addAliases(result, "FOB_PRICE", "FOBPRICE");
        addAliases(result, "CARE_CONTENT_LABEL", "CARECONTENTLABEL", "CAREANDCONTENTLABEL");
        addAliases(result, "CHINA_INSPECTION_TAG", "CHINAINSPECTIONTAG");
        addAliases(result, "FOB_AMOUNT", "FOBAMOUNT");
        addAliases(result, "TOTAL_FOB_PRICE_TAG", "TOTALFOBPRCIETAG", "TOTALFOBPRICETAG");
        addAliases(result, "FOB_FTY_PRICE", "FOBFTYPRICE", "FOBFACTORYPRICE");
        addAliases(result, "FOB_FTY_AMOUNT", "FOBFTYAMOUNT", "FOBFACTORYAMOUNT");
        addAliases(result, "SHIP_MODE", "SHIPMODE", "SHIPPINGMODE");
        addAliases(result, "SEASON", "SEASON");
        addAliases(result, "DP_PERCENT", "DP", "DPPERCENT");
        addAliases(result, "MPR_NUMBER", "MPRNUMBER", "MPRNO", "MPR");
        addAliases(result, "FWD", "FWD", "FORWARDER");
        addAliases(result, "CARTON_BOX_SIZE", "CARTONBOXSIZE", "CARTONSIZE", "BOXSIZE");
        addAliases(result, "NW", "NW", "NETWEIGHT", "NETWEIGHTKG");
        addAliases(result, "GW", "GW", "GROSSWEIGHT", "GROSSWEIGHTKG");
        addAliases(result, "REMARK_2", "REMARK2", "REMARK02");
        addAliases(result, "FACTORY", "FACTORY", "FACTORYCODE", "PACKINGFACTORY", "PRODUCTIONFACILITY", "FTY");
        return Collections.unmodifiableMap(result);
    }

    private static void addAliases(Map<String, Set<String>> target, String canonical, String... aliases) {
        Set<String> values = new LinkedHashSet<>();
        for (String alias : aliases) values.add(TextNormalizer.headerKey(alias));
        target.put(canonical, Collections.unmodifiableSet(values));
    }

    private String canonicalAllBpKey(String rawHeader) {
        String cleanHeader = clean(rawHeader);
        if (cleanHeader == null) return null;
        String asciiHeader = Normalizer.normalize(cleanHeader, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        String key = TextNormalizer.headerKey(asciiHeader);
        if (key == null || key.isEmpty()) return null;
        for (Map.Entry<String, Set<String>> entry : ALL_BP_ALIASES.entrySet()) {
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
                throw new IllegalArgumentException("ALL_BP contains more than one column matching key " + canonical
                        + " (columns " + (previous + 1) + " and " + (col + 1) + "). Rename one of the headers.");
            }
        }
        return result;
    }

    private int requiredCanonicalColumn(Map<String, Integer> columns, String key) {
        Integer col = columns.get(key);
        if (col == null) throw new IllegalArgumentException("Missing required ALL_BP column key: " + key);
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
        Set<String> numericSourceKeys = Set.of(
                "QTY", "PCS_CTN", "ODD_RATIO", "CTNS", "REMAINDER",
                "FOB_PRICE", "CARE_CONTENT_LABEL", "CHINA_INSPECTION_TAG", "FOB_AMOUNT",
                "TOTAL_FOB_PRICE_TAG", "FOB_FTY_PRICE", "FOB_FTY_AMOUNT"
        );
        for (SourceColumn sourceColumn : sourceColumns) {
            String canonical = canonicalAllBpKey(sourceColumn.label());
            String text = clean(excelSupport.text(row, sourceColumn.index(), evaluator));
            // Preserve the actual numeric value for calculated/price columns. This is required
            // because the current ALL_BP formats U:AA as ";;" (visually hidden), while Q is
            // formatted as an integer even though =O/P can contain decimals.
            if (numericSourceKeys.contains(canonical) || text == null) {
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
        if (value == null) throw new IllegalArgumentException("Missing required Excel column: " + String.join(" / ", aliases));
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

    private EditedAllBpPlan buildEditedAllBpPlan(LululemonPo po, LululemonPoUpdateRequest request) {
        List<Map<String, String>> submitted = request.allBpRows();
        if (submitted == null || submitted.isEmpty()) return null;

        List<Map<String, String>> existing = po.getAllBpRows() == null ? List.of() : po.getAllBpRows();
        if (!existing.isEmpty() && submitted.size() != existing.size()) {
            throw new IllegalArgumentException("ALL_BP row count cannot be changed while editing a PO. Re-import the workbook to add/remove source rows.");
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
        int totalQty = 0;

        Map<String, String> shared = new LinkedHashMap<>();
        List<String> mustMatch = List.of(
                "PO", "STYLE", "PCS_CTN", "DC_CODE", "DESTINATION", "CHANNEL", "MASTER_PO",
                "DESCRIPTION", "COLOR", "SIZE", "SHIP_MODE", "SEASON", "FWD", "CARTON_BOX_SIZE"
        );

        for (int index = 0; index < submitted.size(); index++) {
            Map<String, String> base = index < existing.size() && existing.get(index) != null
                    ? existing.get(index) : Map.of();
            Map<String, String> incoming = submitted.get(index) == null ? Map.of() : submitted.get(index);
            Map<String, String> edited = new LinkedHashMap<>();

            for (String header : headers) {
                String canonical = canonicalAllBpKey(header);
                String original = base.get(header);
                if (canonical != null && ALL_BP_FORMULA_KEYS.contains(canonical)) {
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
            String rowPo = requiredRowText(edited, "PO", "PO", rowNo);
            String rowStyle = requiredRowText(edited, "STYLE", "Style#", rowNo);
            int rowQty = requiredPositiveWhole(rowCanonicalValue(edited, "QTY"), "Q'ty", rowNo);
            int rowPcs = requiredPositiveWhole(rowCanonicalValue(edited, "PCS_CTN"), "Pcs/ctn", rowNo);

            recalculateAllBpFormulaColumns(edited, rowQty, rowPcs, rowNo);
            quantities.addAll(cartonQuantities(rowQty, rowPcs));
            totalQty = Math.addExact(totalQty, rowQty);

            if (poNumber == null) poNumber = rowPo;
            else if (!Objects.equals(poNumber, rowPo)) {
                throw new IllegalArgumentException("All ALL_BP source rows for one imported PO must keep the same PO number.");
            }
            if (style == null) style = rowStyle;
            else if (!Objects.equals(style, rowStyle)) {
                throw new IllegalArgumentException("All ALL_BP source rows for one imported PO must keep the same Style#.");
            }
            if (qtyPerCarton == null) qtyPerCarton = rowPcs;
            else if (!Objects.equals(qtyPerCarton, rowPcs)) {
                throw new IllegalArgumentException("All ALL_BP source rows for one imported PO must keep the same Pcs/ctn.");
            }

            for (String key : mustMatch) {
                String current = clean(rowCanonicalValue(edited, key));
                if (index == 0) shared.put(key, current);
                else if (!Objects.equals(shared.get(key), current)) {
                    throw new IllegalArgumentException("ALL_BP field " + key + " must be the same across rows belonging to one PO.");
                }
            }
            rows.add(new LinkedHashMap<>(edited));
        }

        if (quantities.isEmpty()) throw new IllegalArgumentException("Edited PO does not contain any carton quantity plan.");
        int lastQty = quantities.get(quantities.size() - 1);
        int remainder = lastQty < qtyPerCarton ? lastQty : 0;
        Map<String, String> first = rows.get(0);

        Double nw = optionalDouble(rowCanonicalValue(first, "NW"), "NW");
        Double gw = optionalDouble(rowCanonicalValue(first, "GW"), "GW");
        if (nw != null && nw < 0) throw new IllegalArgumentException("N.W. cannot be negative.");
        if (gw != null && gw < 0) throw new IllegalArgumentException("G.W. cannot be negative.");

        return new EditedAllBpPlan(
                poNumber, style, totalQty, qtyPerCarton, List.copyOf(quantities), remainder,
                clean(rowCanonicalValue(first, "MASTER_PO")), clean(rowCanonicalValue(first, "DC_CODE")),
                clean(rowCanonicalValue(first, "DESTINATION")), clean(rowCanonicalValue(first, "CHANNEL")),
                clean(rowCanonicalValue(first, "PACKING_PLAN")), clean(rowCanonicalValue(first, "SALES_ORDER_PTS")),
                clean(rowCanonicalValue(first, "DESCRIPTION")), clean(rowCanonicalValue(first, "COLOR")),
                clean(rowCanonicalValue(first, "SHIP_MODE")), clean(rowCanonicalValue(first, "SEASON")),
                clean(rowCanonicalValue(first, "FWD")), clean(rowCanonicalValue(first, "CARTON_BOX_SIZE")),
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
        if (value == null) throw new IllegalArgumentException(label + " is required in ALL_BP row " + rowNo + ".");
        return value;
    }

    private int requiredPositiveWhole(String raw, String label, int rowNo) {
        BigDecimal value = parseEditableDecimal(raw, label, rowNo, false);
        if (value == null) throw new IllegalArgumentException(label + " is required in ALL_BP row " + rowNo + ".");
        try {
            int result = value.stripTrailingZeros().intValueExact();
            if (result <= 0) throw new IllegalArgumentException(label + " must be greater than 0 in ALL_BP row " + rowNo + ".");
            return result;
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(label + " must be a whole number in ALL_BP row " + rowNo + ".");
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
            throw new IllegalArgumentException(label + " must be numeric in ALL_BP row " + rowNo + ", received '" + raw + "'.");
        }
    }

    private Double optionalDouble(String raw, String label) {
        String value = clean(raw);
        if (value == null) return null;
        try {
            return new BigDecimal(value.replace(",", "")).doubleValue();
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be numeric, received '" + raw + "'.");
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
        if (value == null) throw new IllegalArgumentException(field + " is required at Excel row " + excelRow);
        try {
            int result = value.stripTrailingZeros().intValueExact();
            if (result <= 0) throw new IllegalArgumentException(field + " must be greater than 0 at Excel row " + excelRow);
            return result;
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(field + " must be a whole number at Excel row " + excelRow + ", received " + value);
        }
    }

    private String searchable(LululemonPo po) {
        return String.join(" ", safe(po.getPoNumber()), safe(po.getStyleNumber()), safe(po.getSku()), safe(po.getFactoryCode()),
                safe(po.getColor()), safe(po.getDescription())).toUpperCase(Locale.ROOT);
    }

    private String requireReason(LululemonExceptionRequest request) {
        String reason = clean(request == null ? null : request.reason());
        if (reason == null || reason.length() < 3) {
            throw new IllegalArgumentException("Reason is required for Supervisor/Admin exception actions.");
        }
        return reason;
    }

    private void assertSkuAvailableForPo(String orderId, String poId, String sku) {
        if (sku == null) return;
        poRepository.findByOrderIdAndBuyerCodeAndSku(orderId, BUYER, sku).stream()
                .filter(existing -> !Objects.equals(existing.getId(), poId))
                .findFirst()
                .ifPresent(existing -> {
                    throw new IllegalArgumentException(
                            "SKU " + sku + " is already assigned to PO " + existing.getPoNumber()
                                    + ". Each PO in the Order must use its own SKU."
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
        String normalized = clean.replaceAll("\\D", "");
        if (normalized.isEmpty()) return null;
        if (normalized.length() != 18) {
            throw new IllegalArgumentException("SSCC-18 must contain exactly 18 digits.");
        }
        return normalized;
    }

    private String normalizeFactory(String value) {
        String clean = clean(value);
        return clean == null ? null : clean.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private void passScan(String orderId, String poId, String cartonId, String type, String raw, String message) {
        scanEventRepository.save(event(orderId, poId, cartonId, type, raw, "PASS", message));
    }

    private void rejectScan(String orderId, String poId, String cartonId, String type, String raw, String message) {
        scanEventRepository.save(event(orderId, poId, cartonId, type, raw, "REJECT", message));
    }

    private LululemonScanEvent event(String orderId, String poId, String cartonId, String type, String raw, String result, String message) {
        LululemonScanEvent event = new LululemonScanEvent();
        event.setBuyerCode(BUYER);
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
        auditLogService.log(RequestActor.current(), action, "LULULEMON_CARTON_LOADING", resourceId, description, null, null);
    }

    private static int safeInt(Integer value) { return value == null ? 0 : value; }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String clean(String value) { return TextNormalizer.trimToNull(value); }
    private static String cleanMessage(Exception ex) { return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }

    private record Candidate(LululemonPo po, List<LululemonCarton> cartons) { }
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
            String shipMode,
            String season,
            String fwd,
            String cartonBoxSize,
            Double netWeightKg,
            Double grossWeightKg,
            List<Map<String, String>> rows
    ) { }

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
        boolean compatibleWith(ParsedPo other) {
            return other != null
                    && Objects.equals(factoryCode, other.factoryCode)
                    && Objects.equals(styleNumber, other.styleNumber)
                    && qtyPerCarton == other.qtyPerCarton
                    && Objects.equals(dcCode, other.dcCode)
                    && Objects.equals(destination, other.destination)
                    && Objects.equals(channel, other.channel)
                    && Objects.equals(masterPo, other.masterPo)
                    && Objects.equals(description, other.description)
                    && Objects.equals(color, other.color)
                    && Objects.equals(size, other.size)
                    && Objects.equals(shipMode, other.shipMode)
                    && Objects.equals(season, other.season)
                    && Objects.equals(fwd, other.fwd)
                    && Objects.equals(cartonBoxSize, other.cartonBoxSize);
        }

        ParsedPo mergeQuantity(ParsedPo other) {
            int combinedQty = Math.addExact(totalQty, other.totalQty);
            List<Integer> combinedQuantities = new ArrayList<>(cartonPlannedQuantities);
            combinedQuantities.addAll(other.cartonPlannedQuantities);
            int lastQty = combinedQuantities.isEmpty() ? 0 : combinedQuantities.get(combinedQuantities.size() - 1);
            int combinedRemainder = lastQty < qtyPerCarton ? lastQty : 0;
            List<Map<String, String>> combinedRows = new ArrayList<>(allBpRows);
            combinedRows.addAll(other.allBpRows);
            return new ParsedPo(
                    sourceLineNo, factoryCode, poNumber, styleNumber, combinedQty, qtyPerCarton, combinedQuantities.size(), combinedRemainder,
                    List.copyOf(combinedQuantities), dcCode, destination, channel, masterPo, packingPlan, salesOrderPts, description, color, size, shipMode, season,
                    fwd, cartonBoxSize, netWeight, grossWeight, List.copyOf(combinedRows)
            );
        }
    }
}
