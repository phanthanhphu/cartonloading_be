package org.bsl.cartonloading.buyers.es.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.constants.AppMessages;
import org.bsl.cartonloading.service.RequestActor;
import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.es.enums.PackingOrderStatus;
import org.bsl.cartonloading.buyers.es.enums.WeightStatus;

import org.bsl.cartonloading.buyers.es.dto.PackingOrderRequest;
import org.bsl.cartonloading.buyers.es.dto.PackingOrderResponse;
import org.bsl.cartonloading.buyers.es.enums.CartonScanStatus;
import org.bsl.cartonloading.common.exception.WorkflowNotFoundException;
import org.bsl.cartonloading.common.exception.WorkflowValidationException;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.buyers.es.model.CartonScanTransaction;
import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.bsl.cartonloading.buyers.es.repository.CartonScanTransactionRepository;
import org.bsl.cartonloading.buyers.es.repository.PackingAllocationLineRepository;
import org.bsl.cartonloading.buyers.es.repository.PackingListLineRepository;
import org.bsl.cartonloading.buyers.es.repository.PackingOrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class PackingOrderService {
    private final PackingOrderRepository orderRepository;
    private final BuyerFactoryAccessService factoryAccess;
    private final PackingAllocationLineRepository masterLineRepository;
    private final PackingListLineRepository packingLineRepository;
    private final CartonScanTransactionRepository cartonTransactionRepository;

    public PackingOrderService(
            PackingOrderRepository orderRepository,
            BuyerFactoryAccessService factoryAccess,
            PackingAllocationLineRepository masterLineRepository,
            PackingListLineRepository packingLineRepository,
            CartonScanTransactionRepository cartonTransactionRepository
    ) {
        this.orderRepository = orderRepository;
        this.factoryAccess = factoryAccess;
        this.masterLineRepository = masterLineRepository;
        this.packingLineRepository = packingLineRepository;
        this.cartonTransactionRepository = cartonTransactionRepository;
    }

    public Page<PackingOrderResponse> list(
            String buyerCode,
            String keyword,
            LocalDate orderDate,
            String orderName,
            String createdBy,
            String status,
            String assignmentStatus,
            Boolean completed,
            int page,
            int size
    ) {
        return list(buyerCode, keyword, orderDate, orderName, null, null, createdBy, status, assignmentStatus, completed, page, size);
    }

    public Page<PackingOrderResponse> list(
            String buyerCode,
            String keyword,
            LocalDate orderDate,
            String orderName,
            String supplierName,
            String productionFacility,
            String createdBy,
            String status,
            String assignmentStatus,
            Boolean completed,
            int page,
            int size
    ) {
        String buyer = requireBuyer(buyerCode);
        String keywordKey = key(keyword);
        String orderNameKey = key(orderName);
        String supplierKey = key(supplierName);
        String factoryKey = key(productionFacility);
        boolean lulu = AppConstants.Buyer.LULULEMON.equals(buyer);
        String selectedFactory = lulu && productionFacility != null ? productionFacility.trim().toUpperCase(Locale.ROOT) : "";
        if (lulu && !selectedFactory.isBlank() && !"UNASSIGNED".equals(selectedFactory)
                && !canReadFactory(selectedFactory))
            throw new org.springframework.security.access.AccessDeniedException("Order Factory access denied: " + selectedFactory);
        boolean unrestricted = lulu && factoryAccess.isUnrestricted();
        List<String> accessibleFactories = lulu && !unrestricted
                ? java.util.stream.Stream.concat(factoryAccess.accessibleFactories().stream(),
                    factoryAccess.currentUser().canWeightCheck()
                        ? factoryAccess.currentUser().getWeighingFactoryPermissions().stream()
                        : java.util.stream.Stream.<String>empty())
                    .distinct().filter(this::canReadFactory).toList()
                : List.of();
        if (lulu && "UNASSIGNED".equals(selectedFactory) && !unrestricted)
            throw new org.springframework.security.access.AccessDeniedException("Only Admin can classify unassigned Orders.");
        String createdByKey = key(createdBy);
        String statusKey = normalizedStatus(status);
        String assignmentStatusKey = normalizedStatus(assignmentStatus);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)));

        List<PackingOrderResponse> rows = orderRepository.findByBuyerCode(buyer).stream()
                .filter(order -> !lulu || unrestricted
                        || (order.getFactoryCode() != null && accessibleFactories.contains(order.getFactoryCode().toUpperCase(Locale.ROOT))))
                .map(this::response)
                .filter(order -> !lulu || selectedFactory.isBlank()
                        || ("UNASSIGNED".equals(selectedFactory) ? order.factoryCode() == null || order.factoryCode().isBlank()
                        : selectedFactory.equalsIgnoreCase(order.factoryCode())))
                .filter(order -> orderDate == null || orderDate.equals(order.orderDate()))
                .filter(order -> orderNameKey == null || contains(order.orderName(), orderNameKey))
                .filter(order -> supplierKey == null || contains(order.supplierName(), supplierKey) || contains(order.supplierNumber(), supplierKey))
                .filter(order -> lulu || factoryKey == null || contains(order.productionFacility(), factoryKey))
                .filter(order -> createdByKey == null || contains(order.createdBy(), createdByKey))
                .filter(order -> statusKey == null || statusKey.equals(normalizedStatus(order.status())))
                .filter(order -> assignmentStatusKey == null || assignmentStatusKey.equals(normalizedStatus(order.assignmentStatus())))
                .filter(order -> completed == null || completed.equals(order.completed()))
                .filter(order -> keywordKey == null || matchesKeyword(order, keywordKey))
                .sorted(Comparator
                        .comparing(PackingOrderResponse::orderDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PackingOrderResponse::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

        int from = Math.min((int) pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
    }

    public PackingOrderResponse getResponse(String buyerCode, String id) {
        return response(getEntity(buyerCode, id));
    }

    public PackingOrder getEntity(String buyerCode, String id) {
        String buyer = requireBuyer(buyerCode);
        PackingOrder order = orderRepository.findByIdAndBuyerCode(id, buyer)
                .orElseThrow(() -> new WorkflowNotFoundException(AppMessages.ORDER_NOT_FOUND));
        if (AppConstants.Buyer.LULULEMON.equals(buyer)) {
            if (order.getFactoryCode() == null || order.getFactoryCode().isBlank()) {
                if (!factoryAccess.isUnrestricted())
                    throw new org.springframework.security.access.AccessDeniedException("Order has no Factory. Ask Admin to classify it.");
            } else if (!canReadFactory(order.getFactoryCode())) {
                throw new org.springframework.security.access.AccessDeniedException("Order Factory access denied: " + order.getFactoryCode());
            }
        }
        return order;
    }

    public PackingOrderResponse create(String buyerCode, PackingOrderRequest request) {
        String buyer = requireBuyer(buyerCode);
        LocalDateTime now = LocalDateTime.now();

        PackingOrder entity = new PackingOrder();
        entity.setBuyerCode(buyer);
        entity.setBuyerKey(buyer);
        apply(entity, request);
        assignFactory(buyer, entity, request);
        entity.setCreatedBy(RequestActor.current());
        entity.setUpdatedBy(RequestActor.current());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return response(orderRepository.save(entity));
    }

    public PackingOrderResponse update(String buyerCode, String id, PackingOrderRequest request) {
        PackingOrder entity = getEntity(buyerCode, id);
        entity.setBuyerKey(requireBuyer(buyerCode));
        apply(entity, request);
        assignFactory(requireBuyer(buyerCode), entity, request);
        entity.setUpdatedBy(RequestActor.current());
        entity.setUpdatedAt(LocalDateTime.now());
        return response(orderRepository.save(entity));
    }

    public void delete(String buyerCode, String id) {
        PackingOrder entity = getEntity(buyerCode, id);
        if (cartonTransactionRepository.countByOrderIdAndBuyerCode(entity.getId(), entity.getBuyerCode()) > 0) {
            throw new WorkflowValidationException(AppMessages.DELETE_ORDER_WITH_CARTONS_BLOCKED);
        }
        masterLineRepository.deleteByOrderIdAndBuyerCode(entity.getId(), entity.getBuyerCode());
        packingLineRepository.deleteByOrderIdAndBuyerCode(entity.getId(), entity.getBuyerCode());
        cartonTransactionRepository.deleteByOrderIdAndBuyerCode(entity.getId(), entity.getBuyerCode());
        orderRepository.delete(entity);
    }

    public void touchFromMaster(PackingOrder entity) {
        entity.setBuyerKey(entity.getBuyerCode());
        entity.setUpdatedBy(RequestActor.current());
        entity.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(entity);
    }

    private boolean canReadFactory(String factory) {
        return factoryAccess.canAccessAssignedFactory(factory)
                || (factoryAccess.currentUser().canWeightCheck()
                    && factoryAccess.canAccessWeighingFactory(factory));
    }

    /** Factory is mandatory on new LULULEMON Orders, immutable after it has been set.
     * Admin may explicitly classify legacy unassigned Orders; ES remains untouched.
     */
    private void assignFactory(String buyer, PackingOrder entity, PackingOrderRequest request) {
        if (!AppConstants.Buyer.LULULEMON.equals(buyer)) return;
        String requested = request.factoryCode() == null ? "" : request.factoryCode().trim().toUpperCase(Locale.ROOT);
        if (requested.isBlank()) throw new WorkflowValidationException("Select a Factory for this LULULEMON Order.");
        factoryAccess.assertAssignedFactoryAccess(requested);
        String existing = entity.getFactoryCode();
        if (existing != null && !existing.isBlank() && !existing.equalsIgnoreCase(requested))
            throw new WorkflowValidationException("Order Factory cannot be changed. Create a new Order for another Factory.");
        if ((existing == null || existing.isBlank()) && entity.getId() != null && !factoryAccess.isUnrestricted())
            throw new org.springframework.security.access.AccessDeniedException("Only Admin may classify legacy Orders.");
        entity.setFactoryCode(requested);
    }

    private void apply(PackingOrder entity, PackingOrderRequest request) {
        if (request.orderDate() == null) {
            throw new WorkflowValidationException(AppMessages.START_ORDER_REQUIRED);
        }
        entity.setOrderDate(request.orderDate());
        if (request.endOrderDate() == null) {
            throw new WorkflowValidationException(AppMessages.END_ORDER_REQUIRED);
        }
        if (request.endOrderDate().isBefore(request.orderDate())) {
            throw new WorkflowValidationException(AppMessages.END_ORDER_BEFORE_START);
        }
        entity.setEndOrderDate(request.endOrderDate());
        entity.setOrderName(required(request.orderName(), AppMessages.ORDER_NAME_REQUIRED));
        entity.setSupplierName(trimToNull(request.supplierName()));
        entity.setSupplierNumber(trimToNull(request.supplierNumber()));
        entity.setProductionFacility(trimToNull(request.productionFacility()));
    }

    private PackingOrderResponse response(PackingOrder entity) {
        long masterLineCount = masterLineRepository.countByOrderIdAndBuyerCode(entity.getId(), entity.getBuyerCode());
        long packingLineCount = packingLineRepository.countByOrderIdAndBuyerCode(entity.getId(), entity.getBuyerCode());
        List<CartonScanTransaction> cartons = cartonTransactionRepository.findByOrderIdAndBuyerCode(
                entity.getId(), entity.getBuyerCode()
        );

        List<CartonScanTransaction> activeCartons = cartons.stream()
                .filter(row -> row.getStatus() != CartonScanStatus.CANCELLED)
                .toList();
        long plannedCartonCount = activeCartons.size();
        long assignedCartonCount = activeCartons.stream()
                .filter(row -> row.getFactoryBarcode() != null && !row.getFactoryBarcode().isBlank())
                .count();
        long unassignedCartonCount = Math.max(0L, plannedCartonCount - assignedCartonCount);
        String assignmentStatus = resolveAssignmentStatus(plannedCartonCount, assignedCartonCount);

        long waitingWeightCartonCount = activeCartons.stream()
                .filter(row -> row.getStatus() == CartonScanStatus.WAITING_WEIGHT)
                .count();
        long completedCartonCount = activeCartons.stream()
                .filter(row -> row.getStatus() == CartonScanStatus.COMPLETED
                        || row.getStatus() == CartonScanStatus.WEIGHT_WARNING)
                .count();
        long passWeightCartonCount = activeCartons.stream()
                .filter(row -> row.getStatus() == CartonScanStatus.COMPLETED)
                .filter(row -> WeightStatus.OK.name().equalsIgnoreCase(row.getWeightStatus()))
                .count();
        long failWeightCartonCount = activeCartons.stream()
                .filter(row -> row.getStatus() == CartonScanStatus.WEIGHT_WARNING)
                .count();
        long notWeighedCartonCount = Math.max(0L, plannedCartonCount - completedCartonCount - waitingWeightCartonCount);
        String weightStatus = resolveWeightStatus(plannedCartonCount, completedCartonCount, waitingWeightCartonCount);

        boolean started = waitingWeightCartonCount > 0 || completedCartonCount > 0;
        boolean completed = plannedCartonCount > 0 && completedCartonCount >= plannedCartonCount;
        String status = resolveStatus(masterLineCount, completed, started);

        return new PackingOrderResponse(
                entity.getId(),
                entity.getBuyerCode(),
                resolvedOrderDate(entity),
                entity.getEndOrderDate(),
                entity.getOrderName(),
                entity.getSupplierName(),
                entity.getSupplierNumber(),
                entity.getProductionFacility(),
                masterLineCount,
                packingLineCount,
                status,
                completed,
                plannedCartonCount,
                completedCartonCount,
                assignmentStatus,
                assignedCartonCount,
                unassignedCartonCount,
                weightStatus,
                waitingWeightCartonCount,
                passWeightCartonCount,
                failWeightCartonCount,
                notWeighedCartonCount,
                entity.getCreatedBy(),
                entity.getUpdatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getFactoryCode()
        );
    }


    private String resolveAssignmentStatus(long plannedCartonCount, long assignedCartonCount) {
        if (plannedCartonCount <= 0 || assignedCartonCount <= 0) return PackingOrderStatus.NOT_STARTED.name();
        if (assignedCartonCount >= plannedCartonCount) return PackingOrderStatus.COMPLETED.name();
        return PackingOrderStatus.IN_PROGRESS.name();
    }

    private String resolveWeightStatus(long plannedCartonCount, long completedCartonCount, long waitingWeightCartonCount) {
        if (plannedCartonCount <= 0 || (completedCartonCount <= 0 && waitingWeightCartonCount <= 0)) return PackingOrderStatus.NOT_STARTED.name();
        if (completedCartonCount >= plannedCartonCount) return PackingOrderStatus.COMPLETED.name();
        return PackingOrderStatus.IN_PROGRESS.name();
    }

    private String resolveStatus(long masterLineCount, boolean completed, boolean started) {
        if (masterLineCount <= 0) return PackingOrderStatus.DRAFT.name();
        if (completed) return PackingOrderStatus.COMPLETED.name();
        if (started) return PackingOrderStatus.IN_PROGRESS.name();
        return PackingOrderStatus.NOT_STARTED.name();
    }

    private boolean matchesKeyword(PackingOrderResponse order, String keywordKey) {
        String completionText = order.completed() ? AppConstants.SearchText.YES_COMPLETED : AppConstants.SearchText.NO_NOT_COMPLETED;
        String assignmentText = AppConstants.SearchText.ASSIGN_PREFIX + order.assignmentStatus() + " " + order.assignedCartonCount() + "/" + order.plannedCartonCount();
        String weightText = AppConstants.SearchText.WEIGHT_PREFIX + order.weightStatus() + AppConstants.SearchText.PASS_TOKEN + order.passWeightCartonCount() + AppConstants.SearchText.FAIL_TOKEN + order.failWeightCartonCount();
        return contains(order.orderName(), keywordKey)
                || contains(order.createdBy(), keywordKey)
                || contains(order.status(), keywordKey)
                || contains(order.status().replace('_', ' '), keywordKey)
                || contains(completionText, keywordKey)
                || contains(assignmentText, keywordKey)
                || contains(weightText, keywordKey)
                || (order.orderDate() != null && order.orderDate().toString().contains(keywordKey))
                || (order.endOrderDate() != null && order.endOrderDate().toString().contains(keywordKey));
    }

    private LocalDate resolvedOrderDate(PackingOrder entity) {
        if (entity.getOrderDate() != null) return entity.getOrderDate();
        return entity.getCreatedAt() == null ? null : entity.getCreatedAt().toLocalDate();
    }

    private String requireBuyer(String value) {
        String buyer = BuyerAccess.normalize(value);
        if (buyer.isEmpty()) throw new WorkflowValidationException(AppMessages.unsupportedBuyer(value));
        return buyer;
    }

    private String required(String value, String message) {
        String clean = trimToNull(value);
        if (clean == null) throw new WorkflowValidationException(message);
        return clean;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String clean = value.trim().replaceAll("\\s+", " ");
        return clean.isEmpty() ? null : clean;
    }

    private String key(String value) {
        String clean = trimToNull(value);
        return clean == null ? null : clean.toUpperCase(Locale.ROOT);
    }

    private String normalizedStatus(String value) {
        String clean = key(value);
        return clean == null ? null : clean.replace('-', '_').replace(' ', '_');
    }

    private boolean contains(String value, String needleKey) {
        String source = key(value);
        return source != null && source.contains(needleKey);
    }
}
