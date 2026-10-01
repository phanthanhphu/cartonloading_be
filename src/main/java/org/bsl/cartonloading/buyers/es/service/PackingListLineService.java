package org.bsl.cartonloading.buyers.es.service;

import org.bsl.cartonloading.service.RequestActor;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.bsl.cartonloading.common.importing.ImportMode;
import org.bsl.cartonloading.common.importing.ImportRowError;
import org.bsl.cartonloading.common.importing.SpreadsheetImportResult;
import org.bsl.cartonloading.buyers.es.dto.PackingListGenerationResult;
import org.bsl.cartonloading.buyers.es.dto.PackingListLineRequest;
import org.bsl.cartonloading.common.importing.ImportValidationException;
import org.bsl.cartonloading.common.exception.WorkflowNotFoundException;
import org.bsl.cartonloading.common.exception.WorkflowValidationException;
import org.bsl.cartonloading.buyers.es.model.PackingAllocationLine;
import org.bsl.cartonloading.buyers.es.model.PackingListLine;
import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.bsl.cartonloading.buyers.es.repository.PackingAllocationLineRepository;
import org.bsl.cartonloading.buyers.es.repository.PackingListLineRepository;
import org.bsl.cartonloading.common.importing.ExcelImportSupport;
import org.bsl.cartonloading.common.importing.TextNormalizer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PackingListLineService {
    private static final String MASTER_NAME = "ENGELBERT_STRAUSS_PACKING_LIST";
    private static final int FIRST_DATA_ROW = 11;
    private static final int LAST_SIZE_COLUMN = 36; // AK

    private final PackingListLineRepository repository;
    private final PackingAllocationLineRepository masterRepository;
    private final PackingOrderService orderService;
    private final ExcelImportSupport excelSupport;
    private final CartonLoadingService cartonLoadingService;

    public PackingListLineService(
            PackingListLineRepository repository,
            PackingAllocationLineRepository masterRepository,
            PackingOrderService orderService,
            ExcelImportSupport excelSupport,
            CartonLoadingService cartonLoadingService
    ) {
        this.repository = repository;
        this.masterRepository = masterRepository;
        this.orderService = orderService;
        this.excelSupport = excelSupport;
        this.cartonLoadingService = cartonLoadingService;
    }

    public Page<PackingListLine> list(
            String buyerCode,
            String orderId,
            String keyword,
            String poNumber,
            String articleNumber,
            String styleNumber,
            String color,
            String sizeValue,
            int page,
            int pageSize
    ) {
        PackingOrder order = orderService.getEntity(buyerCode, orderId);
        String keywordKey = key(keyword);
        String poKey = key(poNumber);
        String articleKey = key(articleNumber);
        String styleKey = key(styleNumber);
        String colorKey = key(color);
        String sizeKey = key(sizeValue);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(pageSize, 200)));

        List<PackingListLine> rows = repository.findByOrderIdAndBuyerCode(order.getId(), order.getBuyerCode()).stream()
                .filter(line -> keywordKey == null || matchesKeyword(line, keywordKey))
                .filter(line -> poKey == null || contains(line.getPoNumber(), poKey))
                .filter(line -> articleKey == null || contains(line.getArticleNumber(), articleKey))
                .filter(line -> styleKey == null || contains(line.getStyleNumber(), styleKey) || contains(line.getStyle(), styleKey))
                .filter(line -> colorKey == null || contains(line.getColor(), colorKey))
                .filter(line -> sizeKey == null || contains(line.getSize(), sizeKey))
                .sorted(Comparator.comparing(PackingListLine::getLineNo, Comparator.nullsLast(Integer::compareTo)))
                .collect(Collectors.toList());

        int from = Math.min((int) pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
    }

    public PackingListLine get(String buyerCode, String orderId, String lineId) {
        PackingOrder order = orderService.getEntity(buyerCode, orderId);
        return repository.findByIdAndOrderIdAndBuyerCode(lineId, order.getId(), order.getBuyerCode())
                .orElseThrow(() -> new WorkflowNotFoundException("Packing List row not found"));
    }

    public PackingListLine create(String buyerCode, String orderId, PackingListLineRequest request) {
        PackingOrder order = orderService.getEntity(buyerCode, orderId);
        validate(request);
        boolean refreshPlan = cartonLoadingService.hasGeneratedPlan(order.getBuyerCode(), order.getId());
        if (refreshPlan) cartonLoadingService.assertGeneratedPlanEditable(order.getBuyerCode(), order.getId());
        PackingListLine entity = repository.save(newLine(order, request, nextLineNo(order)));
        if (refreshPlan) cartonLoadingService.refreshGeneratedPlanIfPresent(order.getBuyerCode(), order.getId());
        return entity;
    }

    public PackingListLine update(String buyerCode, String orderId, String lineId, PackingListLineRequest request) {
        PackingListLine entity = get(buyerCode, orderId, lineId);
        validate(request);
        boolean planChanged = physicalPlanChanged(entity, request);
        if (planChanged) cartonLoadingService.assertGeneratedPlanEditable(buyerCode, orderId);
        apply(entity, request);
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setUpdatedBy(RequestActor.current());
        PackingListLine saved = repository.save(entity);
        if (planChanged) cartonLoadingService.refreshGeneratedPlanIfPresent(buyerCode, orderId);
        return saved;
    }

    public void delete(String buyerCode, String orderId, String lineId) {
        PackingListLine entity = get(buyerCode, orderId, lineId);
        boolean refreshPlan = cartonLoadingService.hasGeneratedPlan(buyerCode, orderId);
        if (refreshPlan) cartonLoadingService.assertGeneratedPlanEditable(buyerCode, orderId);
        repository.delete(entity);
        if (refreshPlan) cartonLoadingService.refreshGeneratedPlanIfPresent(buyerCode, orderId);
    }

    public PackingListGenerationResult generateFromMaster(String buyerCode, String orderId, boolean replace) {
        PackingOrder order = orderService.getEntity(buyerCode, orderId);
        boolean refreshPlan = cartonLoadingService.hasGeneratedPlan(order.getBuyerCode(), order.getId());
        if (refreshPlan) cartonLoadingService.assertGeneratedPlanEditable(order.getBuyerCode(), order.getId());
        List<PackingAllocationLine> masterLines = masterRepository.findByOrderIdAndBuyerCode(order.getId(), order.getBuyerCode()).stream()
                .sorted(Comparator.comparing(PackingAllocationLine::getLineNo, Comparator.nullsLast(Integer::compareTo)))
                .toList();
        if (masterLines.isEmpty()) {
            return new PackingListGenerationResult(false, 0, 0, "Import or add Order Items before generating a Packing List.");
        }

        if (replace) repository.deleteByOrderIdAndBuyerCode(order.getId(), order.getBuyerCode());
        int nextLine = nextLineNo(order);
        BigDecimal nextCarton = BigDecimal.ONE;
        int created = 0;
        int skipped = 0;

        for (PackingAllocationLine master : masterLines) {
            BigDecimal cartons = master.getTotalCartons();
            BigDecimal pcs = master.getTotalPcs();
            if (!positive(cartons) || !positive(pcs)) {
                skipped++;
                continue;
            }

            BigDecimal normalizedCartons = cartons.setScale(0, RoundingMode.HALF_UP);
            BigDecimal cartonFrom = nextCarton;
            BigDecimal cartonTo = cartonFrom.add(normalizedCartons).subtract(BigDecimal.ONE);
            BigDecimal cbm = positive(master.getCbmCtn()) ? master.getCbmCtn().multiply(normalizedCartons) : null;

            PackingListLineRequest request = new PackingListLineRequest(
                    cartonFrom,
                    cartonTo,
                    normalizedCartons,
                    master.getPoNumber(),
                    master.getStyleNumber(),
                    master.getStyle(),
                    master.getArticleNumber(),
                    master.getColor(),
                    master.getSize(),
                    master.getQtyPerCarton(),
                    pcs,
                    null,
                    cbm,
                    null,
                    null,
                    null,
                    master.getRemarks()
            );
            PackingListLine generated = newLine(order, request, nextLine++);
            generated.setMasterLineId(master.getId());
            repository.save(generated);
            nextCarton = cartonTo.add(BigDecimal.ONE);
            created++;
        }

        orderService.touchFromMaster(order);
        if (refreshPlan) cartonLoadingService.refreshGeneratedPlanIfPresent(order.getBuyerCode(), order.getId());
        return new PackingListGenerationResult(true, created, skipped,
                "Packing List generated from Order Items. Generated physical cartons were synchronized automatically.");
    }

    public SpreadsheetImportResult upload(String buyerCode, String orderId, MultipartFile file, ImportMode mode) {
        PackingOrder order = orderService.getEntity(buyerCode, orderId);
        if (cartonLoadingService.hasGeneratedPlan(buyerCode, orderId)) {
            throw new WorkflowValidationException("Bulk import is locked after Carton Master generation. Edit individual rows before operations start, or create a new Order.");
        }
        ImportMode effectiveMode = mode == null ? ImportMode.CREATE_ONLY : mode;
        List<ImportRowError> errors = new ArrayList<>();
        List<RowCandidate> candidates = new ArrayList<>();
        int totalRows = 0;

        Map<String, PackingAllocationLine> masterByArticle = masterRepository
                .findByOrderIdAndBuyerCode(order.getId(), order.getBuyerCode()).stream()
                .filter(line -> key(line.getArticleNumber()) != null)
                .collect(Collectors.toMap(line -> key(line.getArticleNumber()), Function.identity(), (first, second) -> first, LinkedHashMap::new));

        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = findPackingSheet(workbook);
            FormulaEvaluator evaluator = excelSupport.evaluator(workbook);
            validatePackingHeader(sheet, evaluator);

            for (int rowIndex = FIRST_DATA_ROW; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;
                String po = clean(excelSupport.text(row, 4, evaluator));
                String article = clean(excelSupport.text(row, 7, evaluator));
                BigDecimal cartonQty = excelSupport.decimal(row, 3, evaluator);
                if (po == null || article == null || cartonQty == null) continue; // summary/blank rows
                totalRows++;
                try {
                    PackingAllocationLine master = masterByArticle.get(key(article));
                    PackingListLineRequest request = toRequest(sheet, row, evaluator, master);
                    validate(request);
                    candidates.add(new RowCandidate(rowIndex + 1, request));
                } catch (RuntimeException ex) {
                    errors.add(new ImportRowError(rowIndex + 1, "row", cleanMessage(ex)));
                }
            }
        } catch (ImportValidationException ex) {
            errors.add(new ImportRowError(1, "file", cleanMessage(ex)));
        } catch (Exception ex) {
            errors.add(new ImportRowError(1, "file", "Cannot import Packing List: " + cleanMessage(ex)));
        }

        if (candidates.isEmpty() && errors.isEmpty()) {
            errors.add(new ImportRowError(1, "file", "The Packing List sheet does not contain data rows"));
        }
        if (!errors.isEmpty()) {
            return SpreadsheetImportResult.rejected(MASTER_NAME, effectiveMode, totalRows, errors);
        }

        SpreadsheetImportResult result = new SpreadsheetImportResult();
        result.setDataset(MASTER_NAME);
        result.setMode(effectiveMode);
        result.setApplied(true);
        result.setTotalRows(totalRows);
        result.setValidRows(candidates.size());

        if (effectiveMode == ImportMode.REPLACE_ALL) {
            repository.deleteByOrderIdAndBuyerCode(order.getId(), order.getBuyerCode());
        }

        if (effectiveMode == ImportMode.UPSERT) {
            Map<String, PackingListLine> existing = new LinkedHashMap<>();
            for (PackingListLine line : repository.findByOrderIdAndBuyerCode(order.getId(), order.getBuyerCode())) {
                existing.putIfAbsent(identityKey(line), line);
            }
            int next = nextLineNo(order);
            for (RowCandidate candidate : candidates) {
                String identity = identityKey(candidate.request());
                PackingListLine entity = existing.get(identity);
                if (entity == null) {
                    entity = repository.save(newLine(order, candidate.request(), next++));
                    existing.put(identity, entity);
                    result.setCreated(result.getCreated() + 1);
                } else {
                    apply(entity, candidate.request());
                    entity.setUpdatedAt(LocalDateTime.now());
                    entity.setUpdatedBy(RequestActor.current());
                    repository.save(entity);
                    result.setUpdated(result.getUpdated() + 1);
                }
            }
        } else {
            int next = nextLineNo(order);
            for (RowCandidate candidate : candidates) {
                repository.save(newLine(order, candidate.request(), next++));
                result.setCreated(result.getCreated() + 1);
            }
        }
        orderService.touchFromMaster(order);
        return result;
    }

    private PackingListLineRequest toRequest(
            Sheet sheet,
            Row row,
            FormulaEvaluator evaluator,
            PackingAllocationLine master
    ) {
        BigDecimal qtyPerCarton = master == null ? null : master.getQtyPerCarton();
        String size = master == null ? null : master.getSize();
        if (qtyPerCarton == null || size == null) {
            SizeValue fallback = readSizeValue(sheet, row, evaluator);
            if (qtyPerCarton == null) qtyPerCarton = fallback.quantity();
            if (size == null) size = fallback.label();
        }

        return new PackingListLineRequest(
                excelSupport.decimal(row, 0, evaluator),
                excelSupport.decimal(row, 2, evaluator),
                excelSupport.decimal(row, 3, evaluator),
                clean(excelSupport.text(row, 4, evaluator)),
                clean(excelSupport.text(row, 5, evaluator)),
                clean(excelSupport.text(row, 6, evaluator)),
                clean(excelSupport.text(row, 7, evaluator)),
                clean(excelSupport.text(row, 8, evaluator)),
                size,
                qtyPerCarton,
                excelSupport.decimal(row, 37, evaluator),
                clean(excelSupport.text(row, 38, evaluator)),
                excelSupport.decimal(row, 39, evaluator),
                excelSupport.decimal(row, 40, evaluator),
                excelSupport.decimal(row, 41, evaluator),
                excelSupport.decimal(row, 42, evaluator),
                null
        );
    }

    private SizeValue readSizeValue(Sheet sheet, Row row, FormulaEvaluator evaluator) {
        for (int column = 9; column <= LAST_SIZE_COLUMN; column++) {
            BigDecimal value = excelSupport.decimal(row, column, evaluator);
            if (positive(value)) {
                String label = firstNonBlank(
                        excelSupport.text(sheet.getRow(9), column, evaluator),
                        excelSupport.text(sheet.getRow(8), column, evaluator),
                        excelSupport.text(sheet.getRow(10), column, evaluator),
                        "Size " + excelSupport.excelColumn(column)
                );
                return new SizeValue(label, value);
            }
        }
        return new SizeValue(null, null);
    }

    private Sheet findPackingSheet(Workbook workbook) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            if (sheet.getSheetName().trim().equalsIgnoreCase("SEA")) return sheet;
        }
        if (workbook.getNumberOfSheets() > 0) return workbook.getSheetAt(0);
        throw new ImportValidationException("Excel does not contain a Packing List sheet");
    }

    private void validatePackingHeader(Sheet sheet, FormulaEvaluator evaluator) {
        Row header = sheet.getRow(8);
        if (header == null) throw new ImportValidationException("Packing List header row was not found");
        requireHeader(header, 0, evaluator, "CTNO");
        requireHeader(header, 3, evaluator, "CTNSQTY");
        requireHeader(header, 4, evaluator, "PO");
        requireHeader(header, 7, evaluator, "ARTNO");
        requireHeader(header, 8, evaluator, "COLOR");
        requireHeader(header, 37, evaluator, "TOTAL");
        requireHeader(header, 38, evaluator, "CTNMEAS");
        requireHeader(header, 39, evaluator, "CBM");
    }

    private void requireHeader(Row row, int column, FormulaEvaluator evaluator, String expectedPrefix) {
        String actual = TextNormalizer.headerKey(excelSupport.text(row, column, evaluator));
        if (actual == null || !actual.startsWith(expectedPrefix)) {
            throw new ImportValidationException(
                    "Invalid Packing List header at column " + excelSupport.excelColumn(column)
                            + ". Received '" + excelSupport.text(row, column, evaluator) + "'"
            );
        }
    }

    /**
     * Synchronizes the Packing List row generated from one Order Item/Master row.
     * This is what makes an edited carton quantity automatically flow through to the
     * already-generated Physical Cartons, instead of leaving stale child-row counts.
     */
    public boolean syncFromMasterLine(PackingAllocationLine master) {
        if (master == null || master.getId() == null) return false;
        List<PackingListLine> all = repository.findByOrderIdAndBuyerCode(master.getOrderId(), master.getBuyerCode());
        List<PackingListLine> linked = all.stream()
                .filter(line -> master.getId().equals(line.getMasterLineId()))
                .toList();
        PackingListLine target = linked.size() == 1 ? linked.get(0) : null;
        if (target == null && linked.isEmpty()) {
            List<PackingListLine> fallback = all.stream().filter(line -> sameBusinessItem(master, line)).toList();
            if (fallback.size() == 1) target = fallback.get(0);
        }
        if (target == null) return false;

        BigDecimal cartons = master.getTotalCartons();
        BigDecimal pcs = master.getTotalPcs();
        if (!positive(cartons) || !positive(pcs) || !positive(master.getQtyPerCarton())) return false;

        BigDecimal normalizedCartons = cartons.setScale(0, RoundingMode.HALF_UP);
        BigDecimal cartonFrom = positive(target.getCartonFrom()) ? target.getCartonFrom() : BigDecimal.ONE;
        BigDecimal cartonTo = cartonFrom.add(normalizedCartons).subtract(BigDecimal.ONE);
        BigDecimal cbm = positive(master.getCbmCtn()) ? master.getCbmCtn().multiply(normalizedCartons) : target.getCbm();
        PackingListLineRequest request = new PackingListLineRequest(
                cartonFrom, cartonTo, normalizedCartons, master.getPoNumber(), master.getStyleNumber(),
                master.getStyle(), master.getArticleNumber(), master.getColor(), master.getSize(),
                master.getQtyPerCarton(), pcs, target.getCartonMeasurement(), cbm, target.getGrossWeightKg(),
                target.getNetWeightKg(), target.getActualWeightKg(), target.getRemarks()
        );

        boolean planChanged = physicalPlanChanged(target, request);
        if (planChanged) cartonLoadingService.assertGeneratedPlanEditable(master.getBuyerCode(), master.getOrderId());
        apply(target, request);
        target.setMasterLineId(master.getId());
        target.setUpdatedAt(LocalDateTime.now());
        target.setUpdatedBy(RequestActor.current());
        repository.save(target);
        if (planChanged) cartonLoadingService.refreshGeneratedPlanIfPresent(master.getBuyerCode(), master.getOrderId());
        return true;
    }


    private boolean physicalPlanChanged(PackingListLine current, PackingListLineRequest next) {
        if (current == null) return true;
        return different(current.getCartonFrom(), next.cartonFrom())
                || different(current.getCartonTo(), next.cartonTo())
                || different(current.getCartonsQty(), next.cartonsQty())
                || different(current.getQtyPerCarton(), next.qtyPerCarton())
                || different(current.getTotalPcs(), next.totalPcs())
                || different(current.getGrossWeightKg(), next.grossWeightKg())
                || differentText(current.getPoNumber(), next.poNumber())
                || differentText(current.getArticleNumber(), next.articleNumber())
                || differentText(current.getStyleNumber(), next.styleNumber())
                || differentText(current.getStyle(), next.style())
                || differentText(current.getColor(), next.color())
                || differentText(current.getSize(), next.size());
    }

    private boolean sameBusinessItem(PackingAllocationLine master, PackingListLine line) {
        return !differentText(master.getPoNumber(), line.getPoNumber())
                && !differentText(master.getArticleNumber(), line.getArticleNumber())
                && !differentText(master.getStyleNumber(), line.getStyleNumber())
                && !differentText(master.getColor(), line.getColor())
                && !differentText(master.getSize(), line.getSize());
    }

    private boolean different(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return left != right;
        return left.compareTo(right) != 0;
    }

    private boolean differentText(String left, String right) {
        String a = clean(left);
        String b = clean(right);
        if (a == null || b == null) return a != b;
        return !a.equalsIgnoreCase(b);
    }

    private PackingListLine newLine(PackingOrder order, PackingListLineRequest request, int lineNo) {
        PackingListLine entity = new PackingListLine();
        entity.setBuyerCode(order.getBuyerCode());
        entity.setOrderId(order.getId());
        entity.setLineNo(lineNo);
        apply(entity, request);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setCreatedBy(RequestActor.current());
        entity.setUpdatedBy(RequestActor.current());
        return entity;
    }

    private void apply(PackingListLine entity, PackingListLineRequest request) {
        entity.setCartonFrom(request.cartonFrom());
        entity.setCartonTo(request.cartonTo());
        entity.setCartonsQty(request.cartonsQty());
        entity.setPoNumber(clean(request.poNumber()));
        entity.setStyleNumber(clean(request.styleNumber()));
        entity.setStyle(clean(request.style()));
        entity.setArticleNumber(clean(request.articleNumber()));
        entity.setColor(clean(request.color()));
        entity.setSize(clean(request.size()));
        entity.setQtyPerCarton(request.qtyPerCarton());
        entity.setTotalPcs(request.totalPcs());
        entity.setCartonMeasurement(clean(request.cartonMeasurement()));
        entity.setCbm(request.cbm());
        entity.setGrossWeightKg(request.grossWeightKg());
        entity.setNetWeightKg(request.netWeightKg());
        entity.setActualWeightKg(request.actualWeightKg());
        entity.setRemarks(clean(request.remarks()));
    }

    private void validate(PackingListLineRequest request) {
        requiredNumber(request.cartonsQty(), "CTNS Qty is required");
        required(request.poNumber(), "P.O. # is required");
        required(request.styleNumber(), "Style # is required");
        required(request.articleNumber(), "Art.no. is required");
        required(request.color(), "Color is required");
        required(request.size(), "Size is required");
        requiredNumber(request.qtyPerCarton(), "Qty/CTN is required");
        requiredNumber(request.totalPcs(), "Total PCS is required");
    }

    private int nextLineNo(PackingOrder order) {
        return repository.findByOrderIdAndBuyerCode(order.getId(), order.getBuyerCode()).stream()
                .map(PackingListLine::getLineNo)
                .filter(value -> value != null)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    private boolean matchesKeyword(PackingListLine line, String keyword) {
        return contains(line.getPoNumber(), keyword)
                || contains(line.getStyleNumber(), keyword)
                || contains(line.getStyle(), keyword)
                || contains(line.getArticleNumber(), keyword)
                || contains(line.getColor(), keyword)
                || contains(line.getSize(), keyword)
                || contains(line.getCartonMeasurement(), keyword)
                || contains(line.getRemarks(), keyword);
    }

    private String identityKey(PackingListLine line) {
        return String.join("|", numberKey(line.getCartonFrom()), numberKey(line.getCartonTo()),
                keyOrBlank(line.getPoNumber()), keyOrBlank(line.getArticleNumber()), keyOrBlank(line.getSize()));
    }

    private String identityKey(PackingListLineRequest request) {
        return String.join("|", numberKey(request.cartonFrom()), numberKey(request.cartonTo()),
                keyOrBlank(request.poNumber()), keyOrBlank(request.articleNumber()), keyOrBlank(request.size()));
    }

    private String numberKey(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String cleaned = clean(value);
            if (cleaned != null) return cleaned;
        }
        return null;
    }

    private String required(String value, String message) {
        String clean = clean(value);
        if (clean == null) throw new WorkflowValidationException(message);
        return clean;
    }

    private void requiredNumber(BigDecimal value, String message) {
        if (value == null) throw new WorkflowValidationException(message);
    }

    private String clean(String value) {
        return TextNormalizer.trimToNull(value);
    }

    private String key(String value) {
        return TextNormalizer.key(value);
    }

    private String keyOrBlank(String value) {
        String result = key(value);
        return result == null ? "" : result;
    }

    private boolean contains(String value, String keyword) {
        String source = key(value);
        return source != null && source.contains(keyword);
    }

    private String cleanMessage(Throwable ex) {
        String message = ex == null ? null : ex.getMessage();
        return message == null || message.isBlank() ? "Invalid data" : message;
    }

    private record SizeValue(String label, BigDecimal quantity) {
    }

    private record RowCandidate(int excelRow, PackingListLineRequest request) {
    }
}
