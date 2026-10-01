package org.bsl.cartonloading.service;

import org.bsl.cartonloading.buyers.es.dto.PackingOrderRequest;
import org.bsl.cartonloading.buyers.es.dto.PackingOrderResponse;
import org.bsl.cartonloading.buyers.es.model.CartonScanTransaction;
import org.bsl.cartonloading.buyers.es.model.PackingAllocationLine;
import org.bsl.cartonloading.buyers.es.model.PackingListLine;
import org.bsl.cartonloading.buyers.es.repository.CartonScanTransactionRepository;
import org.bsl.cartonloading.buyers.es.repository.PackingAllocationLineRepository;
import org.bsl.cartonloading.buyers.es.repository.PackingListLineRepository;
import org.bsl.cartonloading.buyers.es.service.PackingOrderService;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonCarton;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonCartonItem;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonPo;
import org.bsl.cartonloading.buyers.lululemon.repository.LululemonCartonItemRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.LululemonCartonRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.LululemonPoRepository;
import org.bsl.cartonloading.buyers.lululemon.service.LululemonWorkflowService;
import org.bsl.cartonloading.dto.management.CartonManagementRow;
import org.bsl.cartonloading.dto.management.ItemManagementRow;
import org.bsl.cartonloading.dto.management.PoManagementRow;
import org.bsl.cartonloading.model.BuyerAccess;
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
    private final CartonScanTransactionRepository esCartons;
    private final LululemonPoRepository luluPos;
    private final LululemonCartonRepository luluCartons;
    private final LululemonCartonItemRepository luluItems;
    private final LululemonWorkflowService luluWorkflow;
    private final AuditLogService audit;

    public OrderManagementService(PackingOrderService orders, PackingListLineRepository packingLines,
                                  PackingAllocationLineRepository allocationLines, CartonScanTransactionRepository esCartons,
                                  LululemonPoRepository luluPos, LululemonCartonRepository luluCartons,
                                  LululemonCartonItemRepository luluItems, LululemonWorkflowService luluWorkflow,
                                  AuditLogService audit) {
        this.orders = orders;
        this.packingLines = packingLines;
        this.allocationLines = allocationLines;
        this.esCartons = esCartons;
        this.luluPos = luluPos;
        this.luluCartons = luluCartons;
        this.luluItems = luluItems;
        this.luluWorkflow = luluWorkflow;
        this.audit = audit;
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
        audit.log(RequestActor.current(), "CREATE", "ORDER", saved.id(), "Created Order " + saved.orderName() + " for " + code, null, null);
        return saved;
    }

    public PackingOrderResponse updateOrder(String buyer, String id, PackingOrderRequest request) {
        String code = requiredBuyer(buyer);
        PackingOrderResponse saved = orders.update(code, id, request);
        audit.log(RequestActor.current(), "UPDATE", "ORDER", id, "Updated Order " + saved.orderName() + " for " + code, null, null);
        return saved;
    }

    public void deleteOrder(String buyer, String id) {
        String code = requiredBuyer(buyer);
        if (BuyerAccess.LULULEMON.equals(code) && !luluPos.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(id, code).isEmpty()) {
            throw new IllegalArgumentException("Cannot delete an Order that already contains LULULEMON PO data.");
        }
        orders.delete(code, id);
        audit.log(RequestActor.current(), "DELETE", "ORDER", id, "Deleted Order for " + code, null, null);
    }

    public Page<PoManagementRow> listPos(String buyer, String orderId, String poNumber, String factory,
                                             String style, String sku, String destination, String status,
                                             java.time.LocalDate exFtyDate, int page, int size) {
        String code = requiredBuyer(buyer);
        orders.getEntity(code, orderId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));

        if (BuyerAccess.LULULEMON.equals(code)) {
            List<PoManagementRow> rows = luluPos.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, code).stream()
                    .filter(po -> matches(po.getPoNumber(), poNumber))
                    .filter(po -> matches(po.getFactoryCode(), factory))
                    .filter(po -> matches(po.getStyleNumber(), style))
                    .filter(po -> matches(po.getSku(), sku))
                    .filter(po -> matches(po.getDestination(), destination))
                    .filter(po -> matches(po.getStatus(), status))
                    .filter(po -> exFtyDate == null || exFtyDate.equals(po.getExFtyDate()))
                    .map(this::luluPoRow)
                    .toList();
            return page(rows, safePage, safeSize);
        }

        Map<String, List<PackingListLine>> grouped = packingLines.findByOrderIdAndBuyerCode(orderId, code).stream()
                .filter(line -> line.getPoNumber() != null && !line.getPoNumber().isBlank())
                .collect(Collectors.groupingBy(PackingListLine::getPoNumber, LinkedHashMap::new, Collectors.toList()));
        allocationLines.findByOrderIdAndBuyerCode(orderId, code).stream()
                .filter(line -> line.getPoNumber() != null && !line.getPoNumber().isBlank())
                .forEach(line -> grouped.computeIfAbsent(line.getPoNumber(), ignored -> new ArrayList<>(List.of(asPackingLine(line)))));
        List<CartonScanTransaction> cartons = esCartons.findByOrderIdAndBuyerCode(orderId, code);
        List<PoManagementRow> rows = grouped.entrySet().stream()
                .filter(e -> matches(e.getKey(), poNumber))
                .map(e -> {
                    List<PackingListLine> lines = e.getValue();
                    PackingListLine first = lines.get(0);
                    List<CartonScanTransaction> pc = cartons.stream().filter(c -> Objects.equals(e.getKey(), c.getPoNumber())).toList();
                    long itemCount = pc.stream().mapToLong(c -> quantity(c).longValue()).sum();
                    String rowStatus = pc.isEmpty() ? "PLANNED" : "IN_PROGRESS";
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
        if (BuyerAccess.LULULEMON.equals(code)) {
            return luluPos.findByIdAndOrderIdAndBuyerCode(poKey, orderId, code)
                    .map(this::luluPoRow)
                    .orElseThrow(() -> new IllegalArgumentException("PO not found"));
        }
        return listPos(code, orderId, poKey, null, null, null, null, null, null, 0, 200).getContent().stream()
                .filter(row -> Objects.equals(poKey, row.key()) || Objects.equals(poKey, row.poNumber()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("PO not found"));
    }

    public Page<CartonManagementRow> listCartons(String buyer, String orderId, String poKey,
                                                     String cartonNo, String sscc, String status,
                                                     int page, int size) {
        String code = requiredBuyer(buyer);
        orders.getEntity(code, orderId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        if (BuyerAccess.LULULEMON.equals(code)) {
            luluWorkflow.ensureCartonsForPo(code, orderId, poKey);
            List<CartonManagementRow> rows = luluCartons.findByPoIdOrderByCartonNoAsc(poKey).stream()
                    .filter(c -> Objects.equals(orderId, c.getOrderId()) && Objects.equals(code, c.getBuyerCode()))
                    .map(this::luluCartonRow)
                    .filter(r -> matches(String.valueOf(r.cartonNo()), cartonNo))
                    .filter(r -> matches(r.cartonIdentity(), sscc))
                    .filter(r -> matches(r.status(), status))
                    .toList();
            return page(rows, safePage, safeSize);
        }
        List<CartonManagementRow> rows = esCartons.findByOrderIdAndBuyerCodeOrderByOrderCartonSequenceAsc(orderId, code).stream()
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
        if (BuyerAccess.LULULEMON.equals(code)) {
            LululemonCarton carton = luluCartons.findByIdAndOrderIdAndBuyerCode(cartonId, orderId, code)
                    .orElseThrow(() -> new IllegalArgumentException("Carton not found"));
            return luluCartonRow(carton);
        }
        CartonScanTransaction carton = esCartons.findByIdAndOrderIdAndBuyerCode(cartonId, orderId, code)
                .orElseThrow(() -> new IllegalArgumentException("Carton not found"));
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
        if (BuyerAccess.LULULEMON.equals(code)) {
            luluWorkflow.ensureItemsForCarton(code, orderId, cartonId);
            List<ItemManagementRow> rows = luluItems.findByCartonIdOrderByItemNoAsc(cartonId).stream()
                    .filter(i -> Objects.equals(orderId, i.getOrderId()) && Objects.equals(code, i.getBuyerCode()))
                    .map(this::luluItemRow)
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
        CartonScanTransaction c = esCartons.findByIdAndOrderIdAndBuyerCode(cartonId, orderId, code)
                .orElseThrow(() -> new IllegalArgumentException("Carton not found"));
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

    private PoManagementRow luluPoRow(LululemonPo po) {
        long plannedCartons = Optional.ofNullable(po.getCartonCount()).orElse(0);
        long plannedItems = Optional.ofNullable(po.getTotalQty()).orElse(0);
        return new PoManagementRow(
                po.getId(), po.getId(), po.getOrderId(), po.getBuyerCode(), po.getPoNumber(), po.getStyleNumber(), po.getSku(), po.getFactoryCode(), po.getStatus(),
                plannedCartons, plannedItems,
                po.getMasterPo(), po.getDcCode(), po.getDestination(), po.getChannel(), po.getPackingPlan(), po.getSalesOrderPts(), po.getDescription(), po.getColor(), po.getSize(),
                po.getTotalQty(), po.getQtyPerCarton(), po.getCartonCount(), po.getRemainderQty(), po.getShipMode(), po.getSeason(), po.getFwd(), po.getCartonBoxSize(),
                po.getNetWeightKg(), po.getGrossWeightKg(), po.getExFtyDate(), po.getAllBpHeaders(), po.getAllBpRows()
        );
    }

    private CartonManagementRow luluCartonRow(LululemonCarton c) {
        return new CartonManagementRow(c.getId(), c.getOrderId(), c.getBuyerCode(), c.getPoId(), c.getCartonNo(), c.getStatus(), c.getSscc18(),
                c.getPlannedQty(), c.getScannedQty(), null, null);
    }

    private ItemManagementRow luluItemRow(LululemonCartonItem i) {
        return new ItemManagementRow(i.getId(), i.getCartonId(), i.getItemNo(), i.getScannedSku(), null, null, null,
                BigDecimal.ONE, i.getStatus(), i.getScannedBy(), i.getScannedAt());
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
        if (!BuyerAccess.isSupported(code)) throw new IllegalArgumentException("Unsupported Buyer: " + buyer);
        return code;
    }

    private String clean(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
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
