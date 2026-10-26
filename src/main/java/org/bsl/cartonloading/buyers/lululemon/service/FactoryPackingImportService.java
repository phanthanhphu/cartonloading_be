package org.bsl.cartonloading.buyers.lululemon.service;

import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.lululemon.dto.AssignSsccRequest;
import org.bsl.cartonloading.buyers.lululemon.dto.ConfirmLabelRequest;
import org.bsl.cartonloading.buyers.lululemon.model.*;
import org.bsl.cartonloading.buyers.lululemon.repository.*;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.importing.ExcelImportSupport;
import org.bsl.cartonloading.common.importing.TextNormalizer;
import org.bsl.cartonloading.service.RequestActor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;

/** Import SSCC carton identities from a Factory Packing workbook.
 * Columns: PO, Carton No, SSCC, SKU. Master PO / Packing Plan / SO (PTS) may
 * disambiguate identical PO numbers. Existing ALL_BP order plan is authoritative.
 */
@Service
public class FactoryPackingImportService {
    private final ExcelImportSupport excel;
    private final org.bsl.cartonloading.buyers.es.service.PackingOrderService orders;
    private final BuyerFactoryAccessService factories;
    private final PurchaseOrderRepository pos;
    private final CartonRepository cartons;
    private final WorkflowService workflow;
    private final PackingImportBatchRepository batches;

    public FactoryPackingImportService(ExcelImportSupport excel, org.bsl.cartonloading.buyers.es.service.PackingOrderService orders, BuyerFactoryAccessService factories,
                                       PurchaseOrderRepository pos, CartonRepository cartons,
                                       WorkflowService workflow, PackingImportBatchRepository batches) {
        this.excel = excel; this.orders = orders; this.factories = factories; this.pos = pos;
        this.cartons = cartons; this.workflow = workflow; this.batches = batches;
    }

    private void assertFactory(String factory) {
        if (factory == null || factory.isBlank()) throw new IllegalArgumentException("Select a Factory before importing Packing.");
        factories.assertAssignedFactoryAccess(factory);
    }

    private record ImportRow(int line, PurchaseOrder po, int cartonNo, String sscc, String sku) { }

    public List<PackingImportBatch> list(String buyer, String factoryCode) {
        if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(buyer)) throw new IllegalArgumentException("Only LULULEMON supported.");
        String factory = factoryCode == null ? "" : factoryCode.trim().toUpperCase(Locale.ROOT);
        assertFactory(factory);
        return batches.findByBuyerCodeAndFactoryCodeOrderByImportedAtDesc(AppConstants.Buyer.LULULEMON, factory);
    }

    public synchronized PackingImportBatch importWorkbook(String buyer, String orderId, String factoryCode, MultipartFile file) {
        if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(buyer)) throw new IllegalArgumentException("Only LULULEMON supported.");
        if (orderId == null || orderId.isBlank()) throw new IllegalArgumentException("Select an Order.");
        String factory = factoryCode == null ? "" : factoryCode.trim().toUpperCase(Locale.ROOT);
        assertFactory(factory);
        var owner = orders.getEntity(AppConstants.Buyer.LULULEMON, orderId);
        if (!factory.equalsIgnoreCase(owner.getFactoryCode()))
            throw new IllegalArgumentException("Selected Order does not belong to Factory " + factory + ".");
        List<PurchaseOrder> factoryPos = pos.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, AppConstants.Buyer.LULULEMON)
                .stream().filter(po -> factory.equalsIgnoreCase(po.getFactoryCode())).toList();
        if (factoryPos.isEmpty()) throw new IllegalArgumentException("No ALL_BP POs belong to this Order and Factory. Import ALL_BP first.");
        List<ImportRow> rows = new ArrayList<>();
        Set<String> ssccs = new HashSet<>();
        Set<String> cartonKeys = new HashSet<>();
        try (Workbook workbook = excel.openWorkbook(file)) {
            Sheet sheet = workbook.getSheetAt(0);
            FormulaEvaluator evaluator = excel.evaluator(workbook);
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) throw new IllegalArgumentException("Packing sheet must have a header row.");
            Map<String, Integer> columns = new HashMap<>();
            for (int col = 0; col < header.getLastCellNum(); col++) {
                String key = TextNormalizer.headerKey(excel.text(header, col, evaluator));
                if (!key.isEmpty()) columns.putIfAbsent(key, col);
            }
            int poCol = required(columns, "PO", "PONUMBER");
            int cartonCol = required(columns, "CARTONNO", "CARTONNUMBER", "CTNNO");
            int ssccCol = required(columns, "SSCC", "SSCC18");
            int skuCol = required(columns, "SKU", "SKUCODE", "PRODUCTCODE");
            Integer masterCol = optional(columns, "MASTERPO");
            Integer planCol = optional(columns, "PACKINGPLAN");
            Integer soCol = optional(columns, "SOPTS", "SALESORDERPTS");
            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String poNumber = val(row, poCol, evaluator);
                if (poNumber == null) continue;
                String cartonNoValue = val(row, cartonCol, evaluator);
                int cartonNo;
                try { cartonNo = Integer.parseInt(cartonNoValue); }
                catch (Exception e) { throw new IllegalArgumentException("Invalid Carton No at row " + (i + 1)); }
                String rawSscc = val(row, ssccCol, evaluator);
                String sscc = SsccNormalizer.normalize(rawSscc);
                if (sscc == null) throw new IllegalArgumentException("Invalid SSCC-18 at row " + (i + 1));
                String sku = TrueVueDecoder.skuKey(val(row, skuCol, evaluator));
                if (sku == null) throw new IllegalArgumentException("Missing SKU at row " + (i + 1));
                String master = masterCol == null ? null : val(row, masterCol, evaluator);
                String plan = planCol == null ? null : val(row, planCol, evaluator);
                String so = soCol == null ? null : val(row, soCol, evaluator);
                List<PurchaseOrder> matched = factoryPos.stream().filter(p -> poNumber.equalsIgnoreCase(p.getPoNumber()))
                        .filter(p -> master == null || master.equalsIgnoreCase(p.getMasterPo()))
                        .filter(p -> plan == null || plan.equalsIgnoreCase(p.getPackingPlan()))
                        .filter(p -> so == null || so.equalsIgnoreCase(p.getSalesOrderPts())).toList();
                if (matched.size() != 1) throw new IllegalArgumentException("Packing row " + (i + 1)
                        + ": PO must resolve to exactly one ALL_BP PO in Factory " + factory
                        + ". Add Master PO, Packing Plan and SO (PTS) columns if ambiguous.");
                PurchaseOrder po = matched.get(0);
                int planned = po.getPlannedCartons() == null ? 0 : po.getPlannedCartons();
                if (cartonNo <= 0 || cartonNo > planned)
                    throw new IllegalArgumentException("Packing row " + (i + 1) + ": Carton No outside 1.." + planned);
                if (po.getSku() != null && !sku.equals(TrueVueDecoder.skuKey(po.getSku())))
                    throw new IllegalArgumentException("Packing row " + (i + 1) + ": SKU mismatch for PO " + poNumber);
                if (!ssccs.add(sscc) || !cartonKeys.add(po.getId() + ":" + cartonNo))
                    throw new IllegalArgumentException("Duplicate SSCC or Carton No in Packing workbook at row " + (i + 1));
                Carton persisted = cartons.findByPoIdOrderByCartonNoAsc(po.getId()).stream()
                        .filter(c -> Objects.equals(c.getCartonNo(), cartonNo)).findFirst().orElse(null);
                if (persisted != null && persisted.getSscc18() != null && !sscc.equals(persisted.getSscc18()))
                    throw new IllegalArgumentException("Carton " + cartonNo + " already has another SSCC. Reopen through admin review.");
                if (persisted != null && persisted.getLabelConfirmedSku() != null
                        && !sku.equals(TrueVueDecoder.skuKey(persisted.getLabelConfirmedSku())))
                    throw new IllegalArgumentException("Carton " + cartonNo + " already has another SKU.");
                Carton existingSscc = cartons.findBySscc18(sscc).orElse(null);
                if (existingSscc != null && (!po.getId().equals(existingSscc.getPoId())
                        || !Objects.equals(existingSscc.getCartonNo(), cartonNo)))
                    throw new IllegalArgumentException("SSCC " + sscc + " is already assigned to another Carton.");
                rows.add(new ImportRow(i + 1, po, cartonNo, sscc, sku));
                if (rows.size() > 5000) throw new IllegalArgumentException("Packing workbook exceeds 5000 cartons.");
            }
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Unable to read Packing workbook: " + e.getMessage(), e); }
        if (rows.isEmpty()) throw new IllegalArgumentException("Packing workbook contains no Carton rows.");
        // All validation is completed before applying the first change. Preserve all
        // scan/audit evidence for existing imported identities; same-file reimport is safe.
        Map<String, List<Carton>> cached = new HashMap<>();
        for (ImportRow row : rows) {
            List<Carton> c = cached.computeIfAbsent(row.po().getId(), id ->
                    workflow.getPo(buyer, orderId, id).cartons());
            Carton carton = c.stream().filter(x -> Objects.equals(x.getCartonNo(), row.cartonNo())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Carton plan changed during Packing import."));
            if (carton.getSscc18() == null) workflow.assignSscc(buyer, orderId, carton.getId(), new AssignSsccRequest(row.sscc()));
            if (carton.getLabelConfirmedAt() == null) {
                workflow.confirmCartonQuantity(buyer, orderId, carton.getId());
                workflow.confirmCartonLabel(buyer, orderId, carton.getId(), new ConfirmLabelRequest(row.sku()));
            }
        }
        PackingImportBatch batch = new PackingImportBatch();
        batch.setOrderId(orderId);
        batch.setFactoryCode(factory);
        batch.setSourceFileName(file.getOriginalFilename());
        batch.setRowCount(rows.size());
        batch.setImportedBy(RequestActor.current());
        batch.setImportedAt(LocalDateTime.now());
        return batches.save(batch);
    }

    private static Integer optional(Map<String, Integer> cols, String... keys) {
        for (String key : keys) if (cols.containsKey(key)) return cols.get(key);
        return null;
    }
    private static int required(Map<String, Integer> cols, String... keys) {
        Integer col = optional(cols, keys);
        if (col == null) throw new IllegalArgumentException("Packing workbook is missing column " + keys[0]);
        return col;
    }
    private String val(Row row, int col, FormulaEvaluator evaluator) {
        String value = excel.text(row, col, evaluator);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
