package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.es.service.PackingOrderService;
import org.bsl.cartonloading.buyers.lululemon.dto.CheckingResultRequest;
import org.bsl.cartonloading.buyers.lululemon.dto.CheckingSearchResponse;
import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.CartonCheckingRecord;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.repository.CartonCheckingRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.CartonRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.PurchaseOrderRepository;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.service.RequestActor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.*;

/** Carton quality sampling/inspection. Searching never generates or modifies cartons. */
@Service
public class CartonCheckingService {
    private static final String BUYER = AppConstants.Buyer.LULULEMON;
    private static final int MAX_CARTONS = 200;
    private final PackingOrderService orderService;
    private final PurchaseOrderRepository poRepository;
    private final CartonRepository cartonRepository;
    private final CartonCheckingRepository checkingRepository;
    private final BuyerFactoryAccessService factoryAccess;
    private final AuditLogService auditLogService;

    public CartonCheckingService(PackingOrderService orderService,
                                 PurchaseOrderRepository poRepository,
                                 CartonRepository cartonRepository,
                                 CartonCheckingRepository checkingRepository,
                                 BuyerFactoryAccessService factoryAccess,
                                 AuditLogService auditLogService) {
        this.orderService = orderService;
        this.poRepository = poRepository;
        this.cartonRepository = cartonRepository;
        this.checkingRepository = checkingRepository;
        this.factoryAccess = factoryAccess;
        this.auditLogService = auditLogService;
    }

    private void requireScope(String buyer, String orderId) {
        if (!BUYER.equals(BuyerAccess.normalize(buyer))) {
            throw new IllegalArgumentException("Checking is only available for LULULEMON.");
        }
        orderService.getEntity(BUYER, orderId);
    }

    private boolean accessible(String factoryCode) {
        return factoryCode == null || factoryCode.isBlank() || factoryAccess.canAccessFactory(factoryCode);
    }

    private static String required(String value, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException(label + " is required (max 128 characters).");
        }
        return normalized;
    }

    private static boolean identified(Carton carton) {
        // The confirmed label already proves Qty was acknowledged in older versions,
        // which did not persist a separate qtyConfirmedAt timestamp.
        return carton.getLabelConfirmedAt() != null
                && carton.getSscc18() != null && !carton.getSscc18().isBlank()
                && carton.getLabelConfirmedSku() != null && !carton.getLabelConfirmedSku().isBlank();
    }

    /** Legacy Order-scoped API kept for existing clients/bookmarks. */
    public CheckingSearchResponse search(String buyer, String orderId, String type, String input) {
        requireScope(buyer, orderId);
        return searchInternal(orderId, type, input);
    }

    /** Search across all LULULEMON Orders. No active Order selection required. */
    public CheckingSearchResponse searchAcrossOrders(String buyer, String type, String input) {
        requireBuyer(buyer);
        return searchInternal(null, type, input);
    }

    private void requireBuyer(String buyer) {
        if (!BUYER.equals(BuyerAccess.normalize(buyer))) {
            throw new IllegalArgumentException("Checking is only available for LULULEMON.");
        }
    }

    private static boolean sameOrder(String scopedOrderId, String candidateOrderId) {
        return candidateOrderId != null && !candidateOrderId.isBlank()
                && (scopedOrderId == null || scopedOrderId.equals(candidateOrderId));
    }

    private CheckingSearchResponse searchInternal(String orderId, String type, String input) {
        String mode = required(type, "Search mode").toUpperCase(Locale.ROOT);
        String value = required(input, "Search value");
        LinkedHashMap<String, PurchaseOrder> pos = new LinkedHashMap<>();
        LinkedHashMap<String, Carton> cartons = new LinkedHashMap<>();
        switch (mode) {
            case "PO" -> {
                List<PurchaseOrder> matches = orderId == null
                        ? poRepository.findByBuyerCodeAndPoNumber(BUYER, value)
                        : poRepository.findAllByOrderIdAndBuyerCodeAndPoNumber(orderId, BUYER, value);
                for (PurchaseOrder po : matches) {
                    if (!sameOrder(orderId, po.getOrderId()) || !accessible(po.getFactoryCode())) continue;
                    pos.put(po.getId(), po);
                    for (Carton carton : cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId())) {
                        if (sameOrder(orderId, carton.getOrderId()) && po.getOrderId().equals(carton.getOrderId())
                                && BUYER.equals(carton.getBuyerCode()) && accessible(carton.getFactoryCode())) {
                            cartons.put(carton.getId(), carton);
                        }
                    }
                }
            }
            case "SKU" -> {
                String sku = value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                List<PurchaseOrder> matches = orderId == null
                        ? poRepository.findByBuyerCodeAndSkuIgnoreCase(BUYER, sku)
                        : poRepository.findByOrderIdAndBuyerCodeAndSkuIgnoreCase(orderId, BUYER, sku);
                for (PurchaseOrder po : matches) {
                    if (!sameOrder(orderId, po.getOrderId()) || !accessible(po.getFactoryCode())) continue;
                    pos.put(po.getId(), po);
                    for (Carton carton : cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId())) {
                        if (sameOrder(orderId, carton.getOrderId()) && po.getOrderId().equals(carton.getOrderId())
                                && BUYER.equals(carton.getBuyerCode()) && accessible(carton.getFactoryCode())) {
                            cartons.put(carton.getId(), carton);
                        }
                    }
                }
                // A carton can have confirmed SKU even if historical PO SKU was blank.
                List<Carton> skuCartons = orderId == null
                        ? cartonRepository.findByBuyerCodeAndLabelConfirmedSkuIgnoreCase(BUYER, sku)
                        : cartonRepository.findByOrderIdAndBuyerCodeAndLabelConfirmedSkuIgnoreCase(orderId, BUYER, sku);
                for (Carton carton : skuCartons) {
                    if (sameOrder(orderId, carton.getOrderId()) && accessible(carton.getFactoryCode())) {
                        cartons.put(carton.getId(), carton);
                    }
                }
            }
            case "SSCC" -> {
                // GS1 AI (00) and common scanner symbology prefix are accepted.
                String sscc = value.replaceFirst("^\\]C1", "").replaceFirst("^\\(00\\)", "").trim();
                if (sscc.matches("00[0-9]{18}")) sscc = sscc.substring(2);
                if (!sscc.matches("[0-9]{18}")) {
                    throw new IllegalArgumentException("SSCC must contain exactly 18 digits (GS1 (00) prefix is supported).");
                }
                cartonRepository.findBySscc18(sscc).ifPresent(carton -> {
                    if (sameOrder(orderId, carton.getOrderId()) && BUYER.equals(carton.getBuyerCode())
                            && accessible(carton.getFactoryCode())) cartons.put(carton.getId(), carton);
                });
            }
            default -> throw new IllegalArgumentException("Search mode must be PO, SKU or SSCC.");
        }
        // Both PO and Carton factory permissions must match. Cross-order carton linking is forbidden.
        cartons.values().removeIf(carton -> {
            Optional<PurchaseOrder> owningPo = poRepository.findById(carton.getPoId())
                    .filter(po -> BUYER.equals(po.getBuyerCode()))
                    .filter(po -> sameOrder(orderId, po.getOrderId()))
                    .filter(po -> po.getOrderId().equals(carton.getOrderId()))
                    .filter(po -> accessible(po.getFactoryCode()));
            owningPo.ifPresent(po -> pos.put(po.getId(), po));
            return owningPo.isEmpty();
        });
        // Do not surface orphaned records from deleted or foreign Orders.
        Map<String, String> names = new HashMap<>();
        pos.values().removeIf(po -> !loadOrderName(po.getOrderId(), names));
        cartons.values().removeIf(carton -> !pos.containsKey(carton.getPoId()));
        List<CheckingSearchResponse.PoRow> poRows = pos.values().stream()
                .map(po -> new CheckingSearchResponse.PoRow(po.getId(), po.getOrderId(), names.get(po.getOrderId()),
                        po.getPoNumber(), po.getStyle(), po.getColor(), po.getSize(), po.getSku(), po.getPlannedCartons()))
                .toList();
        boolean truncated = cartons.size() > MAX_CARTONS;
        List<CheckingSearchResponse.CartonRow> cartonRows = cartons.values().stream()
                .sorted(Comparator.comparing(Carton::getPoNumber, Comparator.nullsLast(String::compareTo))
                        .thenComparing(Carton::getOrderId, Comparator.nullsLast(String::compareTo))
                        .thenComparing(Carton::getCartonNo, Comparator.nullsLast(Integer::compareTo)))
                .limit(MAX_CARTONS)
                .map(carton -> toCartonRow(carton, pos.get(carton.getPoId()), names.get(carton.getOrderId())))
                .toList();
        return new CheckingSearchResponse(poRows, cartonRows, truncated);
    }

    /** Page ALL existing cartons for the selected PO; unlike Orders browsing this does NOT generate cartons. */
    public Page<CheckingSearchResponse.CartonRow> cartonsForPo(String buyer, String poId, int page, int size) {
        requireBuyer(buyer);
        PurchaseOrder po = poRepository.findById(required(poId, "PO"))
                .filter(p -> BUYER.equals(p.getBuyerCode()) && sameOrder(null, p.getOrderId()))
                .filter(p -> accessible(p.getFactoryCode()))
                .orElseThrow(() -> new IllegalArgumentException("PO not found or access denied."));
        Map<String, String> names = new HashMap<>();
        if (!loadOrderName(po.getOrderId(), names)) {
            throw new IllegalArgumentException("Order for this PO no longer exists.");
        }
        // Filter BEFORE pagination so inaccessible Factory rows are neither returned nor counted.
        List<Carton> all = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId()).stream()
                .filter(c -> BUYER.equals(c.getBuyerCode()) && po.getOrderId().equals(c.getOrderId()))
                .filter(c -> accessible(c.getFactoryCode()))
                .toList();
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        long start = (long) safePage * safeSize;
        List<CheckingSearchResponse.CartonRow> content = start >= all.size() ? List.of()
                : all.subList((int) start, (int) Math.min(all.size(), start + safeSize)).stream()
                    .map(c -> toCartonRow(c, po, names.get(po.getOrderId()))).toList();
        return new PageImpl<>(content, PageRequest.of(safePage, safeSize), all.size());
    }

    private CheckingSearchResponse.CartonRow toCartonRow(Carton carton, PurchaseOrder po, String orderName) {
        CartonCheckingRecord last = checkingRepository
                .findFirstByCartonIdAndOrderIdAndBuyerCodeOrderByCheckedAtDesc(carton.getId(), carton.getOrderId(), BUYER)
                .orElse(null);
        boolean latestStillApplies = last != null
                && Objects.equals(last.getSscc18(), carton.getSscc18())
                && (carton.getLabelConfirmedAt() == null || last.getCheckedAt() == null
                    || !carton.getLabelConfirmedAt().isAfter(last.getCheckedAt()));
        return new CheckingSearchResponse.CartonRow(carton.getId(), carton.getOrderId(), orderName,
                carton.getPoId(), carton.getPoNumber(), carton.getCartonNo(), carton.getSscc18(),
                carton.getLabelConfirmedSku() != null ? carton.getLabelConfirmedSku() : po == null ? null : po.getSku(),
                po == null ? null : po.getStyle(), po == null ? null : po.getColor(), po == null ? null : po.getSize(),
                carton.getPlannedQty(), carton.getStatus(), identified(carton),
                latestStillApplies ? last.getResult() : null,
                latestStillApplies ? last.getCheckedBy() : null,
                latestStillApplies ? last.getCheckedAt() : null);
    }

    private boolean loadOrderName(String orderId, Map<String, String> names) {
        if (names.containsKey(orderId)) return true;
        try {
            var order = orderService.getEntity(BUYER, orderId);
            names.put(orderId, order.getOrderName());
            return true;
        } catch (org.bsl.cartonloading.common.exception.WorkflowNotFoundException ex) {
            return false;
        }
    }

    /** Resolve Order from the carton itself; never trust the operator to choose it. */
    private Carton cartonAcrossOrders(String buyer, String cartonId) {
        requireBuyer(buyer);
        return cartonRepository.findByIdAndBuyerCode(required(cartonId, "Carton"), BUYER)
                .orElseThrow(() -> new IllegalArgumentException("Carton not found for LULULEMON."));
    }

    public List<CartonCheckingRecord> historyAcrossOrders(String buyer, String cartonId) {
        Carton carton = cartonAcrossOrders(buyer, cartonId);
        return history(buyer, carton.getOrderId(), cartonId);
    }

    public CartonCheckingRecord checkAcrossOrders(String buyer, String cartonId, CheckingResultRequest request) {
        Carton carton = cartonAcrossOrders(buyer, cartonId);
        return check(buyer, carton.getOrderId(), cartonId, request);
    }

    public List<CartonCheckingRecord> history(String buyer, String orderId, String cartonId) {
        requireScope(buyer, orderId);
        Carton carton = scopedCarton(orderId, cartonId);
        requireFactory(carton);
        PurchaseOrder po = poRepository.findByIdAndOrderIdAndBuyerCode(carton.getPoId(), orderId, BUYER)
                .orElseThrow(() -> new IllegalArgumentException("PO not found for this carton."));
        requireFactory(po.getFactoryCode());
        return checkingRepository.findTop50ByCartonIdAndOrderIdAndBuyerCodeOrderByCheckedAtDesc(cartonId, orderId, BUYER);
    }

    /** A check records human QC verdict only; it never changes packing, scan or weight status. */
    public CartonCheckingRecord check(String buyer, String orderId, String cartonId, CheckingResultRequest request) {
        requireScope(buyer, orderId);
        Carton carton = scopedCarton(orderId, cartonId);
        requireFactory(carton);
        String outcome = required(request == null ? null : request.result(), "Check result").toUpperCase(Locale.ROOT);
        if (!Set.of(AppConstants.CheckingResult.PASS, AppConstants.CheckingResult.FAIL).contains(outcome)) throw new IllegalArgumentException("Check result must be PASS or FAIL.");
        if (carton.getSscc18() == null || carton.getSscc18().isBlank()) {
            throw new IllegalArgumentException("Assign SSCC to this carton before checking.");
        }
        if (AppConstants.CheckingResult.PASS.equals(outcome) && !identified(carton)) {
            throw new IllegalArgumentException("Complete SSCC, Qty and SKU identification in Packing before recording PASS.");
        }
        String note = request.note() == null ? "" : request.note().trim();
        if (note.length() > 1000) throw new IllegalArgumentException("QC note cannot exceed 1000 characters.");
        if (AppConstants.CheckingResult.FAIL.equals(outcome) && note.length() < 3) throw new IllegalArgumentException("Describe the QC failure (at least 3 characters).");
        PurchaseOrder po = poRepository.findByIdAndOrderIdAndBuyerCode(carton.getPoId(), orderId, BUYER)
                .orElseThrow(() -> new IllegalArgumentException("PO not found for this carton."));
        requireFactory(po.getFactoryCode());

        CartonCheckingRecord record = new CartonCheckingRecord();
        record.setBuyerCode(BUYER);
        record.setOrderId(orderId);
        record.setPoId(po.getId());
        record.setPoNumber(po.getPoNumber());
        record.setCartonId(carton.getId());
        record.setCartonNo(carton.getCartonNo());
        record.setSscc18(carton.getSscc18());
        record.setSku(carton.getLabelConfirmedSku());
        record.setResult(outcome);
        record.setNote(note);
        record.setCheckedBy(RequestActor.current());
        record.setCheckedAt(LocalDateTime.now());
        record = checkingRepository.save(record);
        auditLogService.log(RequestActor.current(), AppConstants.CheckingResult.PASS.equals(outcome)
                        ? AppConstants.AuditAction.CHECKING_PASS : AppConstants.AuditAction.CHECKING_FAIL,
                AppConstants.AuditResource.CARTON_LOADING, carton.getId(),
                "QC " + outcome + " for Order " + orderId + ", PO " + po.getPoNumber()
                        + ", carton " + carton.getCartonNo() + ", SSCC " + carton.getSscc18() + ": " + note, null, null);
        return record;
    }

    private Carton scopedCarton(String orderId, String cartonId) {
        return cartonRepository.findByIdAndOrderIdAndBuyerCode(cartonId, orderId, BUYER)
                .orElseThrow(() -> new IllegalArgumentException("Carton not found in this Order."));
    }

    private void requireFactory(Carton carton) { requireFactory(carton.getFactoryCode()); }
    private void requireFactory(String factoryCode) {
        if (!accessible(factoryCode)) throw new IllegalArgumentException("Factory access denied.");
    }
}
