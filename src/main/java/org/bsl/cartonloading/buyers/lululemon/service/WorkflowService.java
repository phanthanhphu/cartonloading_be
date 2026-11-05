package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.model.CartonProcessingHistory;
import org.bsl.cartonloading.common.constants.AppMessages;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import org.bsl.cartonloading.buyers.lululemon.model.RfidScanSession;
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
import org.bsl.cartonloading.service.SelectedFactoryScope;
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
    private final SelectedFactoryScope selectedFactoryScope;
    private final PoIdentityRegistryService identityRegistry;
    private final LululemonHistoryProjectionService historyProjection;

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
            FactoryCatalogService factories,
            SelectedFactoryScope selectedFactoryScope,
            PoIdentityRegistryService identityRegistry,
            LululemonHistoryProjectionService historyProjection
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
        this.selectedFactoryScope = selectedFactoryScope;
        this.identityRegistry = identityRegistry;
        this.historyProjection = historyProjection;
    }

    // Lululemon Excel ACTION workflow. Only the service layer owns buyer-specific behavior.
    private static final String EXCEL_ACTION_HEADER = AppConstants.ExcelAction.ACTION_COLUMN;
    private static final String EXCEL_ROW_ID_HEADER = AppConstants.ExcelAction.ID_COLUMN;
    private static final String EXCEL_PO_ID = AppConstants.ExcelAction.PO_ID_COLUMN;
    private static final String EXCEL_SOURCE_ROW = AppConstants.ExcelAction.SOURCE_ROW_COLUMN;
    private static final String EXCEL_DATA_VERSION = AppConstants.ExcelAction.DATA_VERSION_COLUMN;
    private static final int MAX_ACTION_ROWS = AppConstants.ExcelAction.MAX_ACTION_ROWS;
    private static boolean isExcelActionMeta(String header) {
        return Set.of(EXCEL_ACTION_HEADER, EXCEL_ROW_ID_HEADER, EXCEL_PO_ID, EXCEL_SOURCE_ROW, EXCEL_DATA_VERSION)
                .contains(Objects.toString(header, "").trim().toUpperCase(Locale.ROOT));
    }
    private String version(PurchaseOrder po) {
        return po.getUpdatedAt() == null ? "" : po.getUpdatedAt().toString();
    }

    /** Output-only layout: A=hidden ID, B=ACTION, then 29 current business columns. */
    private void prependExcelItemId(XSSFWorkbook wb) {
        org.apache.poi.xssf.usermodel.XSSFSheet sheet = wb.getSheet(AppConstants.Excel.ALL_BP_SHEET);
        if (sheet == null) throw new IllegalStateException("Lululemon template sheet is missing");
        Row header = sheet.getRow(1);
        if (header == null || !EXCEL_ACTION_HEADER.equalsIgnoreCase(
                Objects.toString(header.getCell(0).getStringCellValue(), "").trim())) {
            throw new IllegalStateException("Expected official ACTION template header in A2");
        }
        sheet.shiftColumns(0, AppConstants.AllBp.TEMPLATE_BUSINESS_COLUMN_COUNT, 1);
        Row shifted = sheet.getRow(1);
        org.apache.poi.ss.usermodel.Cell idCell = shifted.createCell(0);
        idCell.setCellValue(EXCEL_ROW_ID_HEADER);
        if (shifted.getCell(1) != null) idCell.setCellStyle(shifted.getCell(1).getCellStyle());
        sheet.setColumnWidth(0, 35 * 256);
        // Keep the item-level ID for UPDATE/DELETE, but hide the technical column in Excel.
        sheet.setColumnHidden(0, true);
    }

    private String exportItemId(PurchaseOrder po, int sourceIndex) {
        return po.getId() + "@" + (sourceIndex + 1) + "@" +
                (version(po).isEmpty() ? "LEGACY" : version(po));
    }

    private void restrictExcelActionChoices(XSSFWorkbook wb, String... choices) {
        org.apache.poi.xssf.usermodel.XSSFSheet sheet = wb.getSheet(AppConstants.Excel.ALL_BP_SHEET);
        if (sheet == null) throw new IllegalStateException("Lululemon template sheet is missing");
        // Replace the old CREATE/UPDATE/DELETE dropdown with CREATE-only.
        if (sheet.getCTWorksheet().isSetDataValidations()) {
            sheet.getCTWorksheet().unsetDataValidations();
        }
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createExplicitListConstraint(choices);
        DataValidation validation = helper.createValidation(constraint,
                new CellRangeAddressList(2, MAX_ACTION_ROWS + 1, 1, 1));
        validation.setShowErrorBox(true);
        sheet.addValidationData(validation);
    }

    public byte[] downloadExcelTemplate(String buyerCode, String orderId) {
        requireOrder(buyerCode, orderId);
        try (var input = new ClassPathResource("templates/Lululemon_PO_Import_Template.xlsx").getInputStream();
             XSSFWorkbook wb = new XSSFWorkbook(input);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            prependExcelItemId(wb);
            restrictExcelActionChoices(wb, AppConstants.ExcelAction.CREATE);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Lululemon template not available", e);
        }
    }

    /**
     * Export existing source rows ON TOP OF the official Lululemon template, rather
     * than building a new workbook from historical/imported header labels.
     * All 29 current business headers and their positions come from the
     * downloadable template. Historical FOB/label columns are not re-exported. This is a REFERENCE export, not an update workbook;
     * existing PO changes must be made through the PO management UI.
     */
    public byte[] exportActionExcel(String buyerCode, String orderId) {
        PackingOrder order = requireOrder(buyerCode, orderId);
        String selected = selectedFactoryScope.requested(AppConstants.Buyer.LULULEMON);
        String factory = normalizeFactory(order.getFactoryCode());
        selectedFactoryScope.assertMatches(selected, factory);
        factoryAccess.assertAssignedFactoryAccess(factory);
        List<PurchaseOrder> pos = poRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(
                orderId, AppConstants.Buyer.LULULEMON);

        // Open the same template used by the Download Template endpoint so the
        // exported file retains column order, formatting and ACTION validation.
        try (var input = new ClassPathResource("templates/Lululemon_PO_Import_Template.xlsx").getInputStream();
             XSSFWorkbook wb = new XSSFWorkbook(input);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            prependExcelItemId(wb);
            Sheet sheet = wb.getSheet(AppConstants.Excel.ALL_BP_SHEET);
            if (sheet == null) throw new IllegalStateException("Lululemon template sheet is missing");
            Row header = sheet.getRow(1);
            FormulaEvaluator evaluator = excelSupport.evaluator(wb);
            if (!EXCEL_ROW_ID_HEADER.equalsIgnoreCase(excelSupport.text(header, 0, evaluator))
                    || !EXCEL_ACTION_HEADER.equalsIgnoreCase(excelSupport.text(header, 1, evaluator))) {
                throw new IllegalStateException("Lululemon template must have ID in A2 and ACTION in B2");
            }
            final int businessEnd = header.getLastCellNum(); // ID + ACTION + 29 business columns
            if (businessEnd != AppConstants.AllBp.TEMPLATE_BUSINESS_COLUMN_COUNT + 2)
                throw new IllegalStateException("Lululemon template must have "
                        + AppConstants.AllBp.TEMPLATE_BUSINESS_COLUMN_COUNT + " business columns");
            List<String> businessHeaders = new ArrayList<>();
            Set<String> uniqueHeaders = new HashSet<>();
            for (int c = 2; c < businessEnd; c++) {
                String name = Objects.toString(excelSupport.text(header, c, evaluator), "").trim();
                if (name.isEmpty() || !uniqueHeaders.add(sourceHeaderIdentity(name)))
                    throw new IllegalStateException("Invalid or duplicate Lululemon template column at index " + c);
                businessHeaders.add(name);
            }
            // Exported data must not masquerade as new CREATE rows.
            restrictExcelActionChoices(wb, AppConstants.ExcelAction.CREATE, AppConstants.ExcelAction.UPDATE, AppConstants.ExcelAction.DELETE);
            // The example body from the template is NOT actual Order data; clear
            // all template data values while preserving the prepared cell styles.
            for (int r = 2; r <= sheet.getLastRowNum(); r++) {
                Row templateRow = sheet.getRow(r);
                if (templateRow == null) continue;
                for (int c = 0; c < businessEnd; c++) {
                    org.apache.poi.ss.usermodel.Cell cell = templateRow.getCell(c);
                    if (cell != null) cell.setBlank();
                }
            }
            Row styleRow = sheet.getRow(2);
            int rowIndex = 2;
            for (PurchaseOrder po : pos) {
                if (!Objects.equals(factory, normalizeFactory(po.getFactoryCode()))) continue;
                List<Map<String, String>> rows = po.getAllBpRows();
                if (rows == null || rows.isEmpty()) continue;
                for (int i = 0; i < rows.size(); i++) {
                    if (rowIndex - 2 >= MAX_ACTION_ROWS) throw new IllegalArgumentException(
                            "Export exceeds 15,000 Excel rows. Export a smaller Order.");
                    Row dst = sheet.getRow(rowIndex);
                    if (dst == null) dst = sheet.createRow(rowIndex);
                    // Reuse the template's example-row styles for data beyond its
                    // original preformatted area without copying placeholder data.
                    org.apache.poi.ss.usermodel.Cell actionCell = dst.getCell(0);
                    if (actionCell == null) actionCell = dst.createCell(0);
                    actionCell.setCellValue(exportItemId(po, i));
                    org.apache.poi.ss.usermodel.Cell typeCell = dst.getCell(1);
                    if (typeCell == null) typeCell = dst.createCell(1);
                    typeCell.setCellValue(AppConstants.ExcelAction.UPDATE);
                    Map<String, String> source = rows.get(i);
                    for (int c = 2; c < businessEnd; c++) {
                        org.apache.poi.ss.usermodel.Cell cell = dst.getCell(c);
                        if (cell == null) cell = dst.createCell(c);
                        if (styleRow != null && cell.getCellStyle().getIndex() == 0) {
                            org.apache.poi.ss.usermodel.Cell styleCell = styleRow.getCell(c);
                            if (styleCell != null) cell.setCellStyle(styleCell.getCellStyle());
                        }
                        cell.setCellValue(sourceDisplayValue(source, businessHeaders.get(c - 2)));
                    }
                    rowIndex++;
                }
            }

            if (rowIndex > 2) sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                    1, rowIndex - 1, 0, businessEnd - 1));
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to export Lululemon Excel from the official template", e);
        }
    }

    public record ExcelActionPreview(int creates, int updates, int deletes, List<String> warnings) { }
    public record ExcelActionResult(int createdPos, int createdItems, int updatedPos, int deletedRows, List<String> warnings) { }
    private record ExcelRowAction(int excelRow, String action, String id, Map<String,String> raw) { }
    private record EditPlan(String poId, String expectedVersion, Map<Integer,ExcelRowAction> rowActions,
                            List<ExcelRowAction> additions) { }
    private record ActionPlan(Set<Integer> createLines, List<EditPlan> edits,
                              int creates, int updates, int deletes, List<String> warnings) { }

    public ExcelActionPreview previewActionExcel(String buyerCode, String orderId, MultipartFile file, String mode) {
        ActionPlan plan = readActionPlan(buyerCode, orderId, file, mode);
        return new ExcelActionPreview(plan.creates(), plan.updates(), plan.deletes(), plan.warnings());
    }

    public synchronized ExcelActionResult applyActionExcel(String buyerCode, String orderId, MultipartFile file, String mode) {
        ActionPlan plan = readActionPlan(buyerCode, orderId, file, mode); // Re-validate under the writer lock.
        int createdPos = 0;
        // EDIT supports brand new POs too; existing-PO additions are handled as row-level edits.
        if (!plan.createLines().isEmpty()) {
            createdPos = importAllBpInternal(buyerCode, orderId, file, false, false, plan.createLines()).getCreatedPos();
        }
        int edited = 0;
        int deleted = 0;
        for (EditPlan change : plan.edits()) {
            PurchaseOrder po = requirePo(orderId, change.poId());
            if (!Objects.equals(version(po).isEmpty() ? "LEGACY" : version(po), change.expectedVersion()))
                throw new IllegalArgumentException("PO data changed after export. Download Edit Excel again.");
            List<Map<String,String>> source = po.getAllBpRows();
            List<Map<String,String>> finalRows = new ArrayList<>();
            for (int i = 0; i < source.size(); i++) {
                ExcelRowAction action = change.rowActions().get(i);
                if (action != null && AppConstants.ExcelAction.DELETE.equals(action.action())) {
                    deleted++;
                    continue;
                }
                if (action != null && AppConstants.ExcelAction.UPDATE.equals(action.action())) {
                    finalRows.add(mergeExcelSourceRow(source.get(i), action.raw()));
                    edited++;
                } else finalRows.add(new LinkedHashMap<>(source.get(i)));
            }
            for (ExcelRowAction addition : change.additions()) finalRows.add(addition.raw());
            if (finalRows.isEmpty()) {
                deleteImportedPo(buyerCode, orderId, po.getId());
            } else {
                updateImportedPoInternal(buyerCode, orderId, po.getId(), excelUpdateRequest(po, finalRows), true);
            }
        }
        return new ExcelActionResult(createdPos, plan.creates(), edited, deleted,
                List.of("Excel actions applied. Download Edit Excel again before the next update."));
    }

    /**
     * NEW: the original 29-column Lululemon Sales Excel needs neither ID nor ACTION;
     * every populated PO row is CREATE. Older CREATE-only ACTION workbooks are also
     * accepted, but UPDATE/DELETE are never allowed in NEW mode.
     * EDIT: the legacy API still requires an exported ID and explicit ACTION.
     */
    private ActionPlan readActionPlan(String buyerCode, String orderId, MultipartFile file, String mode) {
        PackingOrder order = requireOrder(buyerCode, orderId);
        String selected = selectedFactoryScope.requested(AppConstants.Buyer.LULULEMON);
        String factory = normalizeFactory(order.getFactoryCode());
        selectedFactoryScope.assertMatches(selected, factory);
        factoryAccess.assertAssignedFactoryAccess(factory);
        boolean editMode = AppConstants.ExcelAction.EDIT_MODE.equalsIgnoreCase(mode);
        if (!editMode && !AppConstants.ExcelAction.NEW_MODE.equalsIgnoreCase(mode))
            throw new IllegalArgumentException("Invalid upload mode. Select Upload New Excel or Upload Edited Excel.");
        Set<Integer> newPoLines = new LinkedHashSet<>();
        Set<String> seenKeys = new HashSet<>();
        Set<String> seenRowIds = new HashSet<>();
        List<PoIdentityRegistryService.Input> newIds = new ArrayList<>();
        Map<String,PurchaseOrder> existingByKey = new LinkedHashMap<>();
        Map<String,PurchaseOrder> existingById = new HashMap<>();
        Map<String, Map<Integer,ExcelRowAction>> rowChanges = new LinkedHashMap<>();
        Map<String, List<ExcelRowAction>> rowAdditions = new LinkedHashMap<>();
        Map<String,String> versions = new LinkedHashMap<>();
        Map<String,PurchaseOrder> affected = new LinkedHashMap<>();
        int createCount = 0, updateCount = 0, deleteCount = 0;
        try (Workbook workbook = excelSupport.openWorkbook(file)) {
            Sheet sheet = excelSupport.requiredSheet(workbook, AppConstants.Excel.ALL_BP_SHEET);
            FormulaEvaluator eval = excelSupport.evaluator(workbook);
            int headerIndex = findHeaderRow(sheet, eval);
            Row header = sheet.getRow(headerIndex);
            Map<String,Integer> columns = resolveAllBpColumns(header, eval);
            int poCol = requiredCanonicalColumn(columns, AppConstants.AllBpKey.PO);
            requiredCanonicalColumn(columns, AppConstants.AllBpKey.MASTER_PO);
            requiredCanonicalColumn(columns, AppConstants.AllBpKey.PACKING_PLAN);
            requiredCanonicalColumn(columns, AppConstants.AllBpKey.SALES_ORDER_PTS);
            Integer actionCol = null, idCol = null;
            for (int c = 0; c < header.getLastCellNum(); c++) {
                String h = clean(excelSupport.text(header, c, eval));
                if (EXCEL_ACTION_HEADER.equalsIgnoreCase(h)) actionCol = c;
                if (EXCEL_ROW_ID_HEADER.equalsIgnoreCase(h)) idCol = c;
            }
            // No ACTION column is the standard Sales file layout in NEW mode.
            // Do not allow an ID-only workbook to bypass the CREATE-only contract.
            if (actionCol == null) {
                if (editMode)
                    throw new IllegalArgumentException("Upload Edited Excel requires ACTION and an exported item ID.");
                if (idCol != null)
                    throw new IllegalArgumentException("Upload New Excel accepts business columns only, without ID; "
                            + "or a legacy template with ACTION=CREATE and blank ID.");
            } else {
                if (!((actionCol == 0 && idCol == null) || (actionCol == 1 && Objects.equals(idCol, 0))))
                    throw new IllegalArgumentException("Invalid Excel layout: ACTION must be in column A "
                            + "(or column B when ID is in A).");
            }
            if (editMode && idCol == null)
                throw new IllegalArgumentException("Upload Edited Excel requires the ID column. Download Edit Excel first.");
            List<SourceColumn> businessColumns = sourceColumns(header, eval);
            if (editMode) for (PurchaseOrder po : poRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(
                    orderId, AppConstants.Buyer.LULULEMON)) {
                if (Objects.equals(factory, normalizeFactory(po.getFactoryCode()))) {
                    existingById.put(po.getId(), po);
                    existingByKey.put(PoIdentityRegistryService.identityId(AppConstants.Buyer.LULULEMON,
                            po.getMasterPo(), po.getPoNumber(), po.getPackingPlan(), po.getSalesOrderPts()), po);
                }
            }
            for (int r = headerIndex + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String poNumber = clean(excelSupport.text(row, poCol, eval));
                String explicitAction = actionCol == null ? null : clean(excelSupport.text(row, actionCol, eval));
                String id = idCol == null ? null : clean(excelSupport.text(row, idCol, eval));
                if (poNumber == null && explicitAction == null && id == null) continue;
                if (poNumber == null) throw new IllegalArgumentException(
                        "Excel row " + (r + 1) + " requires a PO number.");
                if (actionCol != null && explicitAction == null)
                    throw new IllegalArgumentException("Excel row " + (r + 1)
                            + " has a blank ACTION; remove the ACTION column for new files, "
                            + "or explicitly set CREATE.");
                String action = actionCol == null ? AppConstants.ExcelAction.CREATE
                        : explicitAction.toUpperCase(Locale.ROOT);
                if (!Set.of(AppConstants.ExcelAction.CREATE, AppConstants.ExcelAction.UPDATE,
                        AppConstants.ExcelAction.DELETE).contains(action))
                    throw new IllegalArgumentException("Excel row " + (r + 1) + " has invalid ACTION " + action);
                if (!editMode && !AppConstants.ExcelAction.CREATE.equals(action))
                    throw new IllegalArgumentException("Upload New Excel accepts CREATE only (row " + (r + 1) + ").");
                String master = canonicalValue(row, columns, eval, AppConstants.AllBpKey.MASTER_PO);
                String plan = canonicalValue(row, columns, eval, AppConstants.AllBpKey.PACKING_PLAN);
                String so = canonicalValue(row, columns, eval, AppConstants.AllBpKey.SALES_ORDER_PTS);
                String incomingFactory = canonicalValue(row, columns, eval, "FACTORY");
                if (incomingFactory != null && !factory.equals(normalizeFactory(incomingFactory)))
                    throw new IllegalArgumentException("Excel row " + (r + 1) + " belongs to another Factory.");
                Map<String,String> raw = captureAllBpRow(row, businessColumns, eval);
                String key = PoIdentityRegistryService.identityId(AppConstants.Buyer.LULULEMON,
                        master, poNumber, plan, so);
                if (AppConstants.ExcelAction.CREATE.equals(action)) {
                    if (id != null) throw new IllegalArgumentException("CREATE must have a blank ID (row " + (r + 1) + ").");
                    PurchaseOrder samePo = editMode ? existingByKey.get(key) : null;
                    if (samePo != null) {
                        rowAdditions.computeIfAbsent(samePo.getId(), k -> new ArrayList<>())
                                .add(new ExcelRowAction(r + 1, action, null, raw));
                        affected.put(samePo.getId(), samePo);
                        versions.putIfAbsent(samePo.getId(), version(samePo).isEmpty() ? "LEGACY" : version(samePo));
                    } else {
                        if (!seenKeys.add(key)) throw new IllegalArgumentException(
                                "Duplicate new PO identity in Excel at row " + (r + 1));
                        newPoLines.add(r + 1);
                        newIds.add(new PoIdentityRegistryService.Input(master, poNumber, plan, so, factory, orderId));
                    }
                    createCount++;
                } else {
                    if (id == null || !editMode) throw new IllegalArgumentException(
                            "UPDATE/DELETE requires an exported item ID (row " + (r + 1) + ").");
                    if (!seenRowIds.add(id)) throw new IllegalArgumentException("Repeated item ID in Excel: row " + (r + 1));
                    String[] bits = id.split("@", 3);
                    if (bits.length != 3) throw new IllegalArgumentException("Invalid item ID at row " + (r + 1));
                    int index;
                    try { index = Integer.parseInt(bits[1]) - 1; }
                    catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid item ID at row " + (r + 1)); }
                    PurchaseOrder po = existingById.get(bits[0]);
                    if (po == null) throw new IllegalArgumentException(
                            "Item no longer exists in this Buyer/Order/Factory, row " + (r + 1));
                    assertFactoryAccessIfPresent(po.getFactoryCode());
                    if (!Objects.equals(factory, normalizeFactory(po.getFactoryCode())))
                        throw new IllegalArgumentException("Item belongs to another Factory, row " + (r + 1));
                    if (!Objects.equals(bits[2], version(po).isEmpty() ? "LEGACY" : version(po)))
                        throw new IllegalArgumentException("Excel data changed since download (row " + (r + 1)
                                + "). Download Edit Excel again.");
                    if (index < 0 || po.getAllBpRows() == null || index >= po.getAllBpRows().size())
                        throw new IllegalArgumentException("Source item not found at row " + (r + 1));
                    String actualKey = PoIdentityRegistryService.identityId(AppConstants.Buyer.LULULEMON,
                            po.getMasterPo(), po.getPoNumber(), po.getPackingPlan(), po.getSalesOrderPts());
                    if (!actualKey.equals(key)) throw new IllegalArgumentException(
                            "PO identity columns cannot be changed in UPDATE/DELETE (row " + (r + 1) + ").");
                    // A re-upload of the unchanged export must not rewrite PO/carton data.
                    boolean rowChanged = !AppConstants.ExcelAction.UPDATE.equals(action)
                            || hasExcelRowChange(po.getAllBpRows().get(index), raw);
                    if (rowChanged) {
                        Map<Integer,ExcelRowAction> changes = rowChanges.computeIfAbsent(po.getId(), k -> new LinkedHashMap<>());
                        if (changes.putIfAbsent(index, new ExcelRowAction(r + 1, action, id, raw)) != null)
                            throw new IllegalArgumentException("Same source item occurs twice in file: " + id);
                        affected.put(po.getId(), po);
                        versions.put(po.getId(), bits[2]);
                        if (AppConstants.ExcelAction.UPDATE.equals(action)) updateCount++;
                        else deleteCount++;
                    }
                }
                if (createCount + updateCount + deleteCount > MAX_ACTION_ROWS)
                    throw new IllegalArgumentException("Excel exceeds " + MAX_ACTION_ROWS + " rows");
            }
            identityRegistry.preflight(newIds, null);
            List<EditPlan> edits = new ArrayList<>();
            for (PurchaseOrder po : affected.values()) {
                Map<Integer,ExcelRowAction> changes = rowChanges.getOrDefault(po.getId(), Map.of());
                List<ExcelRowAction> adds = rowAdditions.getOrDefault(po.getId(), List.of());
                // Build final source in original order so row-number shifts never target a different item.
                List<Map<String,String>> finalRows = new ArrayList<>();
                for (int i = 0; i < po.getAllBpRows().size(); i++) {
                    ExcelRowAction act = changes.get(i);
                    if (act != null && AppConstants.ExcelAction.DELETE.equals(act.action())) continue;
                    finalRows.add(act != null ? mergeExcelSourceRow(po.getAllBpRows().get(i), act.raw())
                            : new LinkedHashMap<>(po.getAllBpRows().get(i)));
                }
                for (ExcelRowAction add : adds) finalRows.add(add.raw());
                if (!finalRows.isEmpty()) {
                    PoUpdateRequest request = excelUpdateRequest(po, finalRows);
                    buildEditedAllBpPlan(po, request, true); // Validate BEFORE any write.
                    // Do not permit source row removal after operations have started.
                    if (changes.values().stream().anyMatch(a -> AppConstants.ExcelAction.DELETE.equals(a.action()))) {
                        if (clean(po.getPickedBy()) != null || po.getPlannedShippingDate() != null
                                || hasPoOperationalActivity(po) || isAssignedToAnySchedule(po))
                            throw new IllegalArgumentException("Cannot DELETE items from PO " + po.getPoNumber()
                                    + " after picking/packing/shipping has started.");
                    }
                } else {
                    if (po.getPlannedShippingDate() != null || clean(po.getPickedBy()) != null)
                        throw new IllegalArgumentException("Cannot delete picked/shipping PO " + po.getPoNumber());
                    assertPoWeighingNotCompleted(po);
                    assertImportedPoMutable(po);
                    assertPoNotAssignedToOpenSchedule(po);
                }
                edits.add(new EditPlan(po.getId(), versions.get(po.getId()), changes, adds));
            }
            return new ActionPlan(newPoLines, edits, createCount, updateCount, deleteCount,
                    List.of(editMode ? "Changes are validated against exported item IDs and Factory permissions."
                            : "All PO rows are treated as CREATE. Existing PO identities are blocked."));
        } catch (PoImportConflictException | IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to validate Lululemon Excel: " + cleanMessage(ex), ex);
        }
    }

    private PoUpdateRequest excelUpdateRequest(PurchaseOrder po, List<Map<String,String>> rows) {
        return new PoUpdateRequest(po.getPoNumber(), po.getMasterPo(), po.getFactoryCode(), po.getStyleNumber(),
                po.getDescription(), po.getColor(), po.getSize(), po.getTotalQty(), po.getQtyPerCarton(),
                po.getDcCode(), po.getDestination(), po.getChannel(), po.getPackingPlan(), po.getSalesOrderPts(),
                po.getShipMode(), po.getSeason(), po.getFwd(), po.getCartonBoxSize(), po.getNetWeightKg(),
                po.getGrossWeightKg(), po.getExFtyDate(), rows);
    }

    private String sourceHeaderIdentity(String label) {
        String canonical = canonicalAllBpKey(label);
        return canonical != null ? "CANON:" + canonical : "RAW:" + TextNormalizer.headerKey(label);
    }
    private String sourceDisplayValue(Map<String,String> source, String exportedHeader) {
        if (source == null) return "";
        if (source.containsKey(exportedHeader)) return Objects.toString(source.get(exportedHeader), "");
        String key = sourceHeaderIdentity(exportedHeader);
        for (var entry : source.entrySet()) if (key.equals(sourceHeaderIdentity(entry.getKey())))
            return Objects.toString(entry.getValue(), "");
        return "";
    }
    private Map<String,String> mergeExcelSourceRow(Map<String,String> original, Map<String,String> incoming) {
        Map<String,String> result = new LinkedHashMap<>(original);
        for (var change : incoming.entrySet()) {
            String identity = sourceHeaderIdentity(change.getKey());
            String matched = result.keySet().stream().filter(h -> identity.equals(sourceHeaderIdentity(h))).findFirst().orElse(null);
            if (matched != null) result.put(matched, change.getValue());
        }
        return result;
    }

    private boolean hasExcelRowChange(Map<String,String> saved, Map<String,String> incoming) {
        for (var entry : incoming.entrySet()) {
            String canonical = canonicalAllBpKey(entry.getKey());
            if (canonical != null && AppConstants.AllBp.FORMULA_KEYS.contains(canonical)) continue;
            if (saved == null || saved.keySet().stream().noneMatch(h -> sourceHeaderIdentity(h).equals(sourceHeaderIdentity(entry.getKey())))) continue;
            String oldValue = clean(sourceDisplayValue(saved, entry.getKey()));
            String newValue = clean(entry.getValue());
            if (!Objects.equals(oldValue, newValue)) return true;
        }
        return false;
    }

    public ImportResult importAllBp(String buyerCode, String orderId, MultipartFile file, boolean replace) {
        // Uploads are append-only. Ignore the old replace flag to avoid destructive/blocked reimports.
        return importAllBpInternal(buyerCode, orderId, file, false, false, null);
    }

    /** Sales master file: add only new Master PO + PO pairs; never overwrite an existing pair. */
    public ImportResult importSharedAllBp(String buyerCode, String orderId, MultipartFile file) {
        return importAllBpInternal(buyerCode, orderId, file, false, true, null);
    }

    private synchronized ImportResult importAllBpInternal(String buyerCode, String orderId, MultipartFile file,
                                                           boolean replace, boolean sharedImport, Set<Integer> onlySourceRows) {
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
            // Legacy import accepts a normal business-column workbook with no ACTION or ID.
            // Older CREATE-only ACTION workbooks remain supported for compatibility.
            int actionCol = -1;
            int idCol = -1;
            for (int c = 0; c < header.getLastCellNum(); c++) {
                String h = Objects.toString(excelSupport.text(header, c, evaluator), "").trim();
                if (EXCEL_ACTION_HEADER.equalsIgnoreCase(h)) actionCol = c;
                if (EXCEL_ROW_ID_HEADER.equalsIgnoreCase(h)) idCol = c;
            }
            if (actionCol >= 0 && !((actionCol == 0 && idCol < 0) || (actionCol == 1 && idCol == 0)))
                throw new IllegalArgumentException("ACTION must be A, or B when ID is column A.");
            if (actionCol >= 0) for (int r = headerRowIndex + 1; r <= sheet.getLastRowNum(); r++) {
                if (onlySourceRows != null && !onlySourceRows.contains(r + 1)) continue;
                Row sourceRow = sheet.getRow(r);
                if (sourceRow == null) continue;
                String po = clean(excelSupport.text(sourceRow, columns.get(AppConstants.AllBpKey.PO), evaluator));
                if (po == null) continue;
                String actionText = clean(excelSupport.text(sourceRow, actionCol, evaluator));
                String idText = idCol < 0 ? null : clean(excelSupport.text(sourceRow, idCol, evaluator));
                if (!AppConstants.ExcelAction.CREATE.equalsIgnoreCase(actionText) || idText != null)
                    throw new IllegalArgumentException("Excel row " + (r + 1) + ": new imports require ACTION=CREATE and blank ID.");
            }
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
                if (onlySourceRows != null && !onlySourceRows.contains(rowIndex + 1)) continue;
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
        // The indexed buyer-wide registry preflight below replaces the legacy
        // full-Order PO scan for append-only uploads (important for large data).
        List<PurchaseOrder> savedPos = replace && !sharedImport
                ? poRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, AppConstants.Buyer.LULULEMON)
                : List.of();
        // Buyer-wide preflight detects ownership in any Order / Factory, including
        // historical POs created before the registry was introduced.
        List<PoIdentityRegistryService.Input> identities = parsed.stream()
                .map(po -> new PoIdentityRegistryService.Input(po.masterPo(), po.poNumber(),
                        po.packingPlan(), po.salesOrderPts(), po.factoryCode(), orderId))
                .toList();
        identityRegistry.preflight(identities, null);

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

        // Reserve unique four-key identities before any PO is saved. Mongo's
        // unique _id index serializes concurrent imports across JVMs / Factories.
        String actor = RequestActor.current();
        requireOrder(buyerCode, orderId); // Recheck deletion fence after parsing Excel.
        List<PoIdentityRegistryService.Reservation> reservations = identityRegistry.reserve(identities, actor);
        try {
            requireOrder(buyerCode, orderId); // Do not write PO after an Admin starts deleting the Order.
        } catch (RuntimeException ex) {
            identityRegistry.releaseAll(reservations);
            throw ex;
        }
        LocalDateTime now = LocalDateTime.now();
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
        List<PurchaseOrder> persisted;
        try {
            persisted = poRepository.saveAll(poBatch);
        } catch (RuntimeException ex) {
            // Standalone MongoDB saveAll can partially succeed. Reconcile each
            // reservation against persisted _id values before releasing it.
            // If the DB cannot be reached, intentionally leave RESERVED claims
            // in place for administrator investigation rather than risk duplicates.
            try {
                Map<String, PoIdentityRegistryService.Reservation> byId = new HashMap<>();
                for (PoIdentityRegistryService.Reservation claim : reservations) byId.put(claim.id(), claim);
                List<PoIdentityRegistryService.Reservation> confirmedUnsaved = new ArrayList<>();
                for (PurchaseOrder candidate : poBatch) {
                    PoIdentityRegistryService.Input input = PoIdentityRegistryService.from(candidate);
                    String key = PoIdentityRegistryService.identityId(AppConstants.Buyer.LULULEMON,
                            input.masterPo(), input.poNumber(), input.packingPlan(), input.salesOrderPts());
                    // An unknown id is not proof of a failed insert. Keep its
                    // claim RESERVED until an administrator reconciles it.
                    if (candidate.getId() == null) continue;
                    if (poRepository.existsById(candidate.getId())) {
                        identityRegistry.commit(input, candidate.getId(), reservations);
                    } else {
                        PoIdentityRegistryService.Reservation claim = byId.get(key);
                        if (claim != null) confirmedUnsaved.add(claim);
                    }
                }
                identityRegistry.releaseAll(confirmedUnsaved);
            } catch (RuntimeException recoveryFailure) {
                ex.addSuppressed(recoveryFailure);
            }
            throw ex;
        }
        for (PurchaseOrder po : persisted) {
            identityRegistry.commit(PoIdentityRegistryService.from(po), po.getId(), reservations);
        }

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
        String selectedFactory = selectedFactoryScope.requested(AppConstants.Buyer.LULULEMON);
        if (selectedFactory != null) criteria = criteria.and("factoryCode").is(selectedFactory);
        else if (!factoryAccess.isUnrestricted()) {
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
        selectedFactoryScope.assertMatches(selectedFactoryScope.requested(AppConstants.Buyer.LULULEMON), po.getFactoryCode());
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
        return updateImportedPoInternal(buyerCode, orderId, poId, request, false);
    }

    private synchronized PurchaseOrder updateImportedPoInternal(
            String buyerCode, String orderId, String poId, PoUpdateRequest request, boolean allowSourceRowDelete
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

        EditedAllBpPlan allBpPlan = buildEditedAllBpPlan(po, request, allowSourceRowDelete);

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

        PoIdentityRegistryService.Input originalIdentity = new PoIdentityRegistryService.Input(
                originalMasterPo, originalNumber, originalPackingPlan, originalSalesOrderPts, originalFactory, orderId);
        PoIdentityRegistryService.Input changedIdentity = new PoIdentityRegistryService.Input(
                po.getMasterPo(), poNumber, po.getPackingPlan(), po.getSalesOrderPts(), factoryCode, orderId);
        boolean identityChanged = !PoIdentityRegistryService.identityId(AppConstants.Buyer.LULULEMON,
                originalIdentity.masterPo(), originalIdentity.poNumber(), originalIdentity.packingPlan(), originalIdentity.salesOrderPts())
                .equals(PoIdentityRegistryService.identityId(AppConstants.Buyer.LULULEMON,
                changedIdentity.masterPo(), changedIdentity.poNumber(), changedIdentity.packingPlan(), changedIdentity.salesOrderPts()));
        if (identityChanged) identityRegistry.preflight(List.of(changedIdentity), poId);

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

        List<PoIdentityRegistryService.Reservation> editReservation = identityChanged
                ? identityRegistry.reserve(List.of(changedIdentity), RequestActor.current()) : List.of();
        PurchaseOrder saved;
        try {
            saved = poRepository.save(po);
        } catch (RuntimeException ex) {
            identityRegistry.releaseAll(editReservation);
            throw ex;
        }
        if (identityChanged) {
            identityRegistry.commit(changedIdentity, saved.getId(), editReservation);
            PurchaseOrder previousIdentityPo = new PurchaseOrder();
            previousIdentityPo.setId(poId);
            previousIdentityPo.setMasterPo(originalMasterPo);
            previousIdentityPo.setPoNumber(originalNumber);
            previousIdentityPo.setPackingPlan(originalPackingPlan);
            previousIdentityPo.setSalesOrderPts(originalSalesOrderPts);
            identityRegistry.releaseCommitted(previousIdentityPo);
        }

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
        identityRegistry.releaseAfterPoDelete(po);

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
        String selectedFactory = selectedFactoryScope.requested(AppConstants.Buyer.LULULEMON);
        String poNumber = clean(request == null ? null : request.poNumber());
        String sku = normalizeSku(request == null ? null : request.sku());
        if (poNumber == null) throw new IllegalArgumentException(AppMessages.CARTON_LABEL_PO_REQUIRED);
        if (sku == null) throw new IllegalArgumentException(AppMessages.CARTON_LABEL_SKU_REQUIRED);

        List<LabelCandidate> flatCandidates = new ArrayList<>();
        for (PurchaseOrder po : poRepository.findAllByOrderIdAndBuyerCodeAndPoNumber(
                orderId, AppConstants.Buyer.LULULEMON, poNumber)) {
            if (!canAccessFactoryOrUnassigned(po.getFactoryCode())
                    || !selectedFactoryScope.matches(selectedFactory, po.getFactoryCode())) continue;

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
        passScan(orderId, po.getId(), cartonId, AppConstants.ScanType.CARTON_LABEL_SKU,
                request.sku(), "Carton label SKU confirmed");
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
                RfidScanSession.class)) {
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

    /** History queries read ONLY the shared snapshot collection, never PO/Carton/RFID lists. */
    private Criteria historyScope(String buyerCode, String orderId) {
        requireOrder(buyerCode, orderId);
        Criteria scope = Criteria.where("buyerCode").is(AppConstants.Buyer.LULULEMON)
                .and("orderId").is(orderId);
        String selected = selectedFactoryScope.requested(AppConstants.Buyer.LULULEMON);
        if (selected != null) {
            factoryAccess.assertFactoryAccess(selected);
            scope.and("factoryCode").is(selected);
        } else if (!factoryAccess.isUnrestricted()) {
            scope.and("factoryCode").in(factoryAccess.accessibleFactories());
        }
        return scope;
    }

    private List<String> distinctHistoryValues(Criteria scope, String field) {
        return mongoTemplate.findDistinct(Query.query(scope), field,
                CartonProcessingHistory.class, String.class).stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public CartonHistoryFilters historyFilters(String buyerCode, String orderId) {
        Criteria scope = historyScope(buyerCode, orderId);
        return new CartonHistoryFilters(distinctHistoryValues(scope, "masterPo"),
                distinctHistoryValues(scope, "poNumber"),
                distinctHistoryValues(scope, "packingPlan"),
                distinctHistoryValues(scope, "salesOrderPts"));
    }

    /** Indexed, server-side paging and filtering without any cross-collection loop. */
    public Page<CartonHistoryRow> history(String buyerCode, String orderId, String masterPo,
            String poNumber, String packingPlan, String salesOrderPts, String keyword,
            String status, String scannedBy, String weighedBy, String rfidStatus, String weightStatus,
            LocalDate dateFrom, LocalDate dateTo, int page, int size) {
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo))
            throw new IllegalArgumentException("Date From cannot be later than Date To");
        Criteria base = historyScope(buyerCode, orderId);
        List<Criteria> terms = new ArrayList<>();
        terms.add(base);
        if (clean(masterPo) != null) terms.add(Criteria.where("masterPo").is(masterPo.trim()));
        if (clean(poNumber) != null) terms.add(Criteria.where("poNumber").is(poNumber.trim()));
        if (clean(packingPlan) != null) terms.add(Criteria.where("packingPlan").is(packingPlan.trim()));
        if (clean(salesOrderPts) != null) terms.add(Criteria.where("salesOrderPts").is(salesOrderPts.trim()));
        String term = clean(keyword);
        if (term != null) {
            String quoted = java.util.regex.Pattern.quote(term);
            List<Criteria> alternatives = new ArrayList<>();
            for (String field : List.of("sscc18", "sku", "poNumber", "masterPo", "packingPlan", "salesOrderPts")) {
                alternatives.add(Criteria.where(field).regex(quoted, "i"));
            }
            if (term.matches("\\d{1,9}")) alternatives.add(Criteria.where("cartonNo").is(Integer.valueOf(term)));
            terms.add(new Criteria().orOperator(alternatives.toArray(new Criteria[0])));
        }
        if (clean(scannedBy) != null) terms.add(Criteria.where("scannedBy")
                .regex(java.util.regex.Pattern.quote(scannedBy.trim()), "i"));
        if (clean(weighedBy) != null) terms.add(Criteria.where("weighedBy")
                .regex(java.util.regex.Pattern.quote(weighedBy.trim()), "i"));
        if ("PASS".equalsIgnoreCase(safe(rfidStatus)) || "FAILED".equalsIgnoreCase(safe(rfidStatus)))
            terms.add(Criteria.where("rfidStatus").is(rfidStatus.trim().toUpperCase(Locale.ROOT)));
        if ("PASS".equalsIgnoreCase(safe(weightStatus)) || "FAILED".equalsIgnoreCase(safe(weightStatus)))
            terms.add(Criteria.where("weightStatus").is(weightStatus.trim().toUpperCase(Locale.ROOT)));
        if ("PASS".equalsIgnoreCase(safe(status)) || "FAILED".equalsIgnoreCase(safe(status)))
            terms.add(Criteria.where("status").is(status.trim().toUpperCase(Locale.ROOT)));
        if (dateFrom != null) terms.add(Criteria.where("weightTime").gte(dateFrom.atStartOfDay()));
        if (dateTo != null) terms.add(Criteria.where("weightTime").lt(dateTo.plusDays(1).atStartOfDay()));
        Criteria combined = terms.size() == 1 ? base : new Criteria().andOperator(terms.toArray(new Criteria[0]));
        Pageable paging = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        Query query = Query.query(combined);
        long count = mongoTemplate.count(query, CartonProcessingHistory.class);
        query.with(Sort.by(Sort.Order.desc("weightTime"), Sort.Order.desc("_id"))).with(paging);
        List<CartonHistoryRow> rows = mongoTemplate.find(query, CartonProcessingHistory.class).stream()
                .map(row -> new CartonHistoryRow(row.getCartonId(), row.getCartonNo(), row.getPoId(),
                        row.getCartonStatus(), row.getOrderName(), row.getFactoryCode(), row.getMasterPo(),
                        row.getPoNumber(), row.getPackingPlan(), row.getSalesOrderPts(), row.getSscc18(),
                        row.getSku(), row.getPlannedQty(), row.getRfidStatus(), row.getWeightStatus(),
                        row.getStatus(), row.getScannedBy(), row.getWeighedBy(), row.getScanTime(),
                        row.getWeightTime(), row.getUpdatedAt())).toList();
        return new PageImpl<>(rows, paging, count);
    }

    /** Manual ADMIN repair of one completed carton after an interrupted standalone-Mongo write.
     * Never run reconciliation automatically during a History GET or on a timer.
     */
    public void reconcileCompletedHistory(String buyerCode, String orderId, String cartonId) {
        requireOrder(buyerCode, orderId);
        Carton carton = requireCarton(orderId, cartonId);
        factoryAccess.assertFactoryAccess(carton.getFactoryCode());
        selectedFactoryScope.assertMatches(selectedFactoryScope.requested(AppConstants.Buyer.LULULEMON),
                carton.getFactoryCode());
        if (!isCompletedHistoryCarton(carton))
            throw new IllegalArgumentException("Only completed cartons can be reconciled into History");
        PurchaseOrder po = requirePo(orderId, carton.getPoId());
        Query latest = Query.query(Criteria.where("buyerCode").is(AppConstants.Buyer.LULULEMON)
                .and("orderId").is(orderId).and("cartonId").is(cartonId)
                .and("action").in(AppConstants.WeightAction.WEIGH, AppConstants.WeightAction.OVERRIDE))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(1);
        WeighingResult saved = mongoTemplate.findOne(latest, WeighingResult.class);
        if (saved == null || !Objects.equals(saved.getCreatedAt(), carton.getWeighedAt())
                || !Objects.equals(saved.getResult(), carton.getWeightStatus()))
            throw new IllegalStateException("Current finalized Weight event missing; no History repair performed");
        historyProjection.recordCompleted(carton, po, saved);
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.WEIGHT_RESULT_PREFIX + carton.getWeightStatus(), cartonId);
    }

    static boolean isCompletedHistoryCarton(Carton carton) {
        return carton != null
                && (AppConstants.RfidCheck.PASS.equals(carton.getRfidStatus())
                    || AppConstants.RfidCheck.FAILED.equals(carton.getRfidStatus()))
                && (AppConstants.WeightStatus.PASS.equals(carton.getWeightStatus())
                    || AppConstants.WeightStatus.FAILED.equals(carton.getWeightStatus()))
                && carton.getWeighedAt() != null;
    }

    /** Detail lookup checks the Factory again; callers cannot retrieve cross-Factory EPCs. */
    public TraceResult historyDetail(String buyerCode, String orderId, String cartonId) {
        requireOrder(buyerCode, orderId);
        Carton carton = requireCarton(orderId, cartonId);
        // An arbitrary carton ID must not expose planned/incomplete cartons via History Detail.
        if (!isCompletedHistoryCarton(carton))
            throw new IllegalArgumentException("Carton is not yet in completed History");
        if (!mongoTemplate.exists(Query.query(Criteria.where("_id")
                .is(CartonProcessingHistory.key(AppConstants.Buyer.LULULEMON, cartonId))
                .and("orderId").is(orderId).and("buyerCode").is(AppConstants.Buyer.LULULEMON)),
                CartonProcessingHistory.class))
            throw new IllegalArgumentException("Carton has no completed History snapshot");
        factoryAccess.assertFactoryAccess(carton.getFactoryCode());
        selectedFactoryScope.assertMatches(selectedFactoryScope.requested(AppConstants.Buyer.LULULEMON),
                carton.getFactoryCode());
        Query poQuery = Query.query(Criteria.where("_id").is(carton.getPoId())
                .and("orderId").is(orderId).and("buyerCode").is(AppConstants.Buyer.LULULEMON));
        poQuery.fields().include("factoryCode").include("poNumber").include("masterPo")
                .include("packingPlan").include("salesOrderPts").include("sku")
                .include("style").include("color").include("size");
        PurchaseOrder po = mongoTemplate.findOne(poQuery, PurchaseOrder.class);
        if (po == null) throw new IllegalArgumentException("PO not found in this Order");
        factoryAccess.assertFactoryAccess(po.getFactoryCode());
        if (!Objects.equals(po.getFactoryCode(), carton.getFactoryCode()))
            throw new org.springframework.security.access.AccessDeniedException("History Factory mismatch");
        // Detail is fetched for one explicitly selected carton; include all saved attempts,
        // not only the latest 200 from the legacy quick-trace endpoints.
        List<ScanEvent> allScans = mongoTemplate.find(Query.query(Criteria.where("cartonId")
                        .is(carton.getId()).and("buyerCode").is(AppConstants.Buyer.LULULEMON))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")), ScanEvent.class);
        List<WeighingResult> allWeights = mongoTemplate.find(Query.query(Criteria.where("cartonId")
                        .is(carton.getId()))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")), WeighingResult.class);
        List<RfidScanSession> allRfid = mongoTemplate.find(Query.query(Criteria.where("cartonId")
                        .is(carton.getId()).and("buyerCode").is(AppConstants.Buyer.LULULEMON))
                .with(Sort.by(Sort.Direction.DESC, "startedAt")), RfidScanSession.class);
        return new TraceResult(po, carton, itemRepository.findByCartonIdOrderByItemNoAsc(carton.getId()),
                allScans, allWeights, allRfid);
    }

    public List<TraceResult> trace(String buyerCode, String orderId, String keyword) {
        requireOrder(buyerCode, orderId);
        String selectedFactory = selectedFactoryScope.requested(AppConstants.Buyer.LULULEMON);
        String raw = clean(keyword);
        // Blank keyword means recent cartons of the chosen Order / permitted Factory.
        // History may be opened right after scanning without knowing an SSCC to search.
        if (raw == null) {
            Criteria criteria = Criteria.where("orderId").is(orderId)
                    .and("buyerCode").is(AppConstants.Buyer.LULULEMON);
            if (selectedFactory != null) criteria.and("factoryCode").is(selectedFactory);
            else if (!factoryAccess.isUnrestricted())
                criteria.and("factoryCode").in(factoryAccess.accessibleFactories());
            // A bounded, indexed query avoids loading an entire large Order before opening History.
            Query recentQuery = Query.query(criteria)
                    .with(Sort.by(Sort.Direction.DESC, "updatedAt")).limit(100);
            Map<String, PurchaseOrder> cachedPos = new HashMap<>();
            List<TraceResult> recent = new ArrayList<>();
            for (Carton carton : mongoTemplate.find(recentQuery, Carton.class)) {
                if (!canAccessFactoryOrUnassigned(carton.getFactoryCode())) continue;
                PurchaseOrder po = cachedPos.computeIfAbsent(carton.getPoId(), id -> requirePo(orderId, id));
                recent.add(new TraceResult(po, carton,
                    itemRepository.findByCartonIdOrderByItemNoAsc(carton.getId()),
                    scanEventRepository.findTop200ByCartonIdOrderByCreatedAtDesc(carton.getId()),
                    weightEventsForCarton(carton.getId()), rfidSessionsForCarton(carton.getId())));
            }
            return recent;
        }
        String skuKey = normalizeSku(raw);
        LinkedHashMap<String, Carton> matchedCartons = new LinkedHashMap<>();
        LinkedHashMap<String, PurchaseOrder> matchedPos = new LinkedHashMap<>();

        String canonicalSscc = SsccNormalizer.normalize(raw);
        if (canonicalSscc != null) cartonRepository.findBySscc18(canonicalSscc).ifPresent(carton -> {
            if (orderId.equals(carton.getOrderId()) && AppConstants.Buyer.LULULEMON.equals(carton.getBuyerCode()) && canAccessFactoryOrUnassigned(carton.getFactoryCode())
                    && selectedFactoryScope.matches(selectedFactory, carton.getFactoryCode())) {
                matchedCartons.put(carton.getId(), carton);
            }
        });
        cartonRepository.findById(raw).ifPresent(carton -> {
            if (orderId.equals(carton.getOrderId()) && AppConstants.Buyer.LULULEMON.equals(carton.getBuyerCode()) && canAccessFactoryOrUnassigned(carton.getFactoryCode())
                    && selectedFactoryScope.matches(selectedFactory, carton.getFactoryCode())) {
                matchedCartons.put(carton.getId(), carton);
            }
        });

        String lowered = raw.toLowerCase(Locale.ROOT);
        for (PurchaseOrder po : poRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, AppConstants.Buyer.LULULEMON)) {
            if (!canAccessFactoryOrUnassigned(po.getFactoryCode())
                    || !selectedFactoryScope.matches(selectedFactory, po.getFactoryCode())) continue;
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
                results.add(new TraceResult(po, carton, items, events, weightEventsForCarton(carton.getId()), rfidSessionsForCarton(carton.getId())));
            }
            return results;
        }

        for (PurchaseOrder po : matchedPos.values()) {
            List<Carton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId());
            if (cartons.isEmpty()) {
                results.add(new TraceResult(po, null, List.of(), scanEventRepository.findTop200ByPoIdOrderByCreatedAtDesc(po.getId()), weightEventsForPo(po.getId()), List.of()));
                continue;
            }
            for (Carton carton : cartons) {
                results.add(new TraceResult(
                        po, carton, itemRepository.findByCartonIdOrderByItemNoAsc(carton.getId()),
                        scanEventRepository.findTop200ByCartonIdOrderByCreatedAtDesc(carton.getId()),
                        weightEventsForCarton(carton.getId()), rfidSessionsForCarton(carton.getId())
                ));
            }
        }
        return results.stream().limit(200).toList();
    }

    private List<RfidScanSession> rfidSessionsForCarton(String cartonId) {
        if (clean(cartonId) == null) return List.of();
        return mongoTemplate.find(Query.query(Criteria.where("cartonId").is(cartonId)
                        .and("buyerCode").is(AppConstants.Buyer.LULULEMON))
                .with(Sort.by(Sort.Direction.DESC, "startedAt")).limit(200), RfidScanSession.class);
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
        String selectedFactory = selectedFactoryScope.requested(AppConstants.Buyer.LULULEMON);
        Set<String> allowedFactories = new HashSet<>(factoryAccess.accessibleFactories());
        // Factory is mandatory on new Orders; unassigned historical events are Admin-only.
        boolean unrestricted = factoryAccess.isUnrestricted();
        Map<String, String> poFactory = poRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, AppConstants.Buyer.LULULEMON).stream()
                .collect(Collectors.toMap(PurchaseOrder::getId, PurchaseOrder::getFactoryCode, (a, b) -> a));
        return scanEventRepository.findTop100ByOrderIdAndBuyerCodeOrderByCreatedAtDesc(orderId, AppConstants.Buyer.LULULEMON).stream()
                .filter(event -> selectedFactoryScope.matches(selectedFactory,
                        event.getPoId() == null ? null : poFactory.get(event.getPoId())))
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
                || (factoryAccess.currentUser().canLululemonScanWeight()
                    && factoryAccess.canAccessWeighingFactory(normalized));
    }

    private void assertFactoryAccessIfPresent(String factoryCode) {
        String normalized = normalizeFactory(factoryCode);
        if (normalized != null && !factoryAccess.canAccessFactory(normalized)
                && !(factoryAccess.currentUser().canLululemonScanWeight()
                    && factoryAccess.canAccessWeighingFactory(normalized)))
            throw new org.springframework.security.access.AccessDeniedException("Factory access denied: " + normalized);
    }

    private PackingOrder requireOrder(String buyerCode, String orderId) {
        String buyer = BuyerAccess.normalize(buyerCode);
        if (!AppConstants.Buyer.LULULEMON.equals(buyer)) throw new IllegalArgumentException(AppMessages.workflowOnlyFor(AppConstants.Buyer.LULULEMON));
        PackingOrder order = orderService.getEntity(AppConstants.Buyer.LULULEMON, orderId);
        if (AppConstants.OrderDeletion.DELETING.equals(order.getDeletionState())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Order deletion is in progress. Contact Admin before importing or processing this Order.");
        }
        return order;
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
            String canonical = canonicalAllBpKey(label);
            if (label == null || isExcelActionMeta(label)
                    || (canonical != null && AppConstants.AllBp.RETIRED_KEYS.contains(canonical))) continue;
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
        return buildEditedAllBpPlan(po, request, false);
    }

    private EditedAllBpPlan buildEditedAllBpPlan(PurchaseOrder po, PoUpdateRequest request, boolean allowSourceRowDelete) {
        List<Map<String, String>> submitted = request.allBpRows();
        if (submitted == null || submitted.isEmpty()) return null;

        List<Map<String, String>> existing = po.getAllBpRows() == null ? List.of() : po.getAllBpRows();
        if (allowSourceRowDelete && submitted.isEmpty())
            throw new IllegalArgumentException("An edited PO must retain at least one item.");
        if (!allowSourceRowDelete && !existing.isEmpty() && submitted.size() != existing.size()) {
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
            Map<String, String> base = allowSourceRowDelete
                    ? (submitted.get(index) == null ? Map.of() : submitted.get(index))
                    : (index < existing.size() && existing.get(index) != null ? existing.get(index) : Map.of());
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

        // FOB and price/label fields were retired from the Lululemon import format.
        // Only carton quantity calculations remain; historical FOB values in MongoDB
        // are intentionally not migrated or deleted.
        putCanonicalRowValue(row, "ODD_RATIO", plainDecimal(oddRatio));
        putCanonicalRowValue(row, "CTNS", Integer.toString(fullCartons));
        putCanonicalRowValue(row, "REMAINDER", Integer.toString(remainder));
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

    /** Every successful direct SSCC/SKU identification creates immutable scan evidence, even for repeat scans. */
    public void recordCartonIdentification(Carton carton, String scannedSku) {
        if (carton == null || carton.getOrderId() == null) return;
        assertFactoryAccessIfPresent(carton.getFactoryCode());
        passScan(carton.getOrderId(), carton.getPoId(), carton.getId(), "SSCC_SKU_IDENTIFY",
                carton.getSscc18(), "SSCC-18 and SKU verified: " + Objects.toString(scannedSku, ""));
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
