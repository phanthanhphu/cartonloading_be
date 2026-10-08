package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.constants.AppMessages;
import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.bsl.cartonloading.buyers.es.service.PackingOrderService;
import org.bsl.cartonloading.buyers.lululemon.dto.PrintRequestCreateRequest;
import org.bsl.cartonloading.buyers.lululemon.dto.PrintRequestStatusRequest;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.model.PrintRequest;
import org.bsl.cartonloading.buyers.lululemon.model.PrintRequestPo;
import org.bsl.cartonloading.buyers.lululemon.repository.PurchaseOrderRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.PrintRequestRepository;
import org.bsl.cartonloading.common.exception.WorkflowNotFoundException;
import org.bsl.cartonloading.common.exception.WorkflowValidationException;
import org.bsl.cartonloading.common.socket.AppSocketPublisher;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.model.Department;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.DepartmentRepository;
import org.bsl.cartonloading.repository.UserRepository;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.service.RequestActor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class PrintRequestService {

    private final PrintRequestRepository repository;
    private final PurchaseOrderRepository poRepository;
    private final PackingOrderService orderService;
    private final BuyerFactoryAccessService factoryAccess;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final MongoTemplate mongoTemplate;
    private final AuditLogService auditLogService;
    private final AppSocketPublisher socketPublisher;

    public PrintRequestService(
            PrintRequestRepository repository,
            PurchaseOrderRepository poRepository,
            PackingOrderService orderService,
            BuyerFactoryAccessService factoryAccess,
            UserRepository userRepository,
            DepartmentRepository departmentRepository,
            MongoTemplate mongoTemplate,
            AuditLogService auditLogService,
            AppSocketPublisher socketPublisher
    ) {
        this.repository = repository;
        this.poRepository = poRepository;
        this.orderService = orderService;
        this.factoryAccess = factoryAccess;
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.mongoTemplate = mongoTemplate;
        this.auditLogService = auditLogService;
        this.socketPublisher = socketPublisher;
    }

    public PrintRequest create(String buyerCode, String orderId, PrintRequestCreateRequest request) {
        String buyer = requireSupportedBuyer(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, required(orderId, AppMessages.required("Order")));
        List<String> poIds = request == null || request.poIds() == null ? List.of() : request.poIds().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();

        if (poIds.isEmpty()) throw new WorkflowValidationException(AppMessages.HANDOFF_SELECT_PO);
        if (poIds.size() > 200) throw new WorkflowValidationException(AppMessages.HANDOFF_MAX_PO);

        List<PurchaseOrder> pos = new ArrayList<>();
        for (String poId : poIds) {
            PurchaseOrder po = poRepository.findByIdAndOrderIdAndBuyerCode(poId, orderId, buyer)
                    .orElseThrow(() -> new WorkflowValidationException(AppMessages.handoffPoNotInOrder(poId)));
            assertFactoryAccessIfPresent(po.getFactoryCode());
            pos.add(po);
        }

        Set<String> requestFactories = pos.stream()
                .map(PurchaseOrder::getFactoryCode)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (requestFactories.size() > 1) {
            throw new WorkflowValidationException(AppMessages.HANDOFF_SINGLE_FACTORY);
        }

        rejectAlreadyQueued(buyer, poIds);

        String actorEmail = RequestActor.current();
        User actor = userRepository.findByEmail(actorEmail).orElse(null);
        String packingUser = actor != null && actor.getUsername() != null && !actor.getUsername().isBlank()
                ? actor.getUsername().trim() : actorEmail;
        LocalDateTime now = LocalDateTime.now();

        PrintRequest entity = new PrintRequest();
        entity.setRequestNo(generateRequestNo(now));
        entity.setBuyerCode(buyer);
        entity.setOrderId(orderId);
        entity.setOrderName(order.getOrderName());
        entity.setPackingUser(packingUser);
        entity.setPackingEmail(actorEmail);
        entity.setPackingDepartment(resolveDepartment(actor));
        entity.setPackingNote(clean(request == null ? null : request.note()));
        entity.setFactoryCodes(new ArrayList<>(requestFactories));
        entity.setPos(pos.stream().map(this::snapshot).toList());
        entity.setPoCount(pos.size());
        entity.setStatus(AppConstants.PrintRequestStatus.SENT);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        PrintRequest saved = repository.save(entity);
        auditLogService.log(actorEmail, AppConstants.AuditAction.SEND_PO_HANDOFF, AppConstants.AuditResource.PO_HANDOFF, saved.getId(),
                "Sent " + saved.getPoCount() + " PO(s) to Print Room. Print Request " + saved.getRequestNo(), null, null);
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.PO_HANDOFF_SENT, saved.getId());
        return saved;
    }

    public Page<PrintRequest> list(
            String buyerCode,
            String orderId,
            String requestNo,
            String packingUser,
            String factoryCode,
            String poNumber,
            String status,
            Boolean mine,
            int page,
            int size
    ) {
        String buyer = requireSupportedBuyer(buyerCode);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)), Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Criteria> criteria = new ArrayList<>();
        criteria.add(Criteria.where("buyerCode").is(buyer));

        if (hasText(orderId)) criteria.add(Criteria.where("orderId").is(orderId.trim()));
        if (hasText(requestNo)) criteria.add(Criteria.where("requestNo").regex(escape(requestNo), "i"));
        if (hasText(packingUser)) {
            String regex = escape(packingUser);
            criteria.add(new Criteria().orOperator(
                    Criteria.where("packingUser").regex(regex, "i"),
                    Criteria.where("packingEmail").regex(regex, "i"),
                    Criteria.where("packingDepartment").regex(regex, "i")
            ));
        }
        if (hasText(factoryCode)) criteria.add(Criteria.where("factoryCodes").regex(escape(factoryCode), "i"));
        if (hasText(poNumber)) criteria.add(Criteria.where("pos.poNumber").regex(escape(poNumber), "i"));
        if (hasText(status)) criteria.add(Criteria.where("status").is(normalizeStatus(status)));
        if (Boolean.TRUE.equals(mine)) criteria.add(Criteria.where("packingEmail").is(RequestActor.current()));

        // PRINT_ROOM is a central receiving role. A Print Room account must see
        // all handoffs for the Buyer it can access even when it is not assigned
        // to a Packing Factory (F1-F7). This exception applies only to Print Request
        // read access; it does not grant Packing access to other factories.
        boolean printRoomViewer = factoryAccess.currentUser().canPrintRoom();
        if (!factoryAccess.isUnrestricted() && !printRoomViewer) {
            List<String> factories = factoryAccess.accessibleFactories();
            List<Criteria> factoryVisibility = new ArrayList<>();
            if (!factories.isEmpty()) factoryVisibility.add(Criteria.where("factoryCodes").in(factories));
            // Factory is optional for LULULEMON; unassigned print requests remain visible.
            factoryVisibility.add(Criteria.where("factoryCodes").exists(false));
            factoryVisibility.add(Criteria.where("factoryCodes").size(0));
            criteria.add(new Criteria().orOperator(factoryVisibility.toArray(Criteria[]::new)));
        }

        Query query = new Query();
        if (!criteria.isEmpty()) query.addCriteria(new Criteria().andOperator(criteria.toArray(Criteria[]::new)));
        long total = mongoTemplate.count(query, PrintRequest.class);
        query.with(pageable);
        List<PrintRequest> rows = mongoTemplate.find(query, PrintRequest.class);
        populateMissingPoAttributes(rows);
        return new PageImpl<>(rows, pageable, total);
    }

    public PrintRequest get(String buyerCode, String requestId) {
        PrintRequest entity = requireRequest(requireSupportedBuyer(buyerCode), requestId);
        assertRequestFactoryAccess(entity);
        populateMissingPoAttributes(List.of(entity));
        return entity;
    }

    public PrintRequest updateStatus(String buyerCode, String requestId, PrintRequestStatusRequest request) {
        String buyer = requireSupportedBuyer(buyerCode);
        PrintRequest entity = requireRequest(buyer, requestId);
        assertRequestFactoryAccess(entity);

        User actor = factoryAccess.currentUser();
        if (!actor.isAdminRole() && !actor.canPrintRoom()) {
            throw new WorkflowValidationException(AppMessages.HANDOFF_STATUS_PERMISSION);
        }

        String current = normalizeStatus(entity.getStatus());
        String target = normalizeStatus(request == null ? null : request.status());
        boolean allowed =
                (AppConstants.PrintRequestStatus.SENT.equals(current) || AppConstants.PrintRequestStatus.PENDING.equals(current))
                        && AppConstants.PrintRequestStatus.PRINTING.equals(target)
                || (AppConstants.PrintRequestStatus.SENT.equals(current)
                        || AppConstants.PrintRequestStatus.PENDING.equals(current)
                        || AppConstants.PrintRequestStatus.PRINTING.equals(current))
                        && AppConstants.PrintRequestStatus.PRINTED_SENT_TO_PACKING.equals(target);
        if (!allowed) throw new WorkflowValidationException(AppMessages.HANDOFF_STATUS_INVALID);

        LocalDateTime now = LocalDateTime.now();
        entity.setStatus(target);
        entity.setStatusUpdatedBy(RequestActor.current());
        entity.setStatusUpdatedAt(now);
        entity.setStatusNote(clean(request == null ? null : request.note()));
        entity.setUpdatedAt(now);
        PrintRequest saved = repository.save(entity);
        auditLogService.log(RequestActor.current(), AppConstants.AuditAction.UPDATE_PO_HANDOFF_STATUS, AppConstants.AuditResource.PO_HANDOFF, saved.getId(),
                "Print Request " + saved.getRequestNo() + " status " + current + " -> " + target, null, null);
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.PO_HANDOFF_STATUS_CHANGED, saved.getId());
        return saved;
    }

    public PrintRequest cancel(String buyerCode, String requestId) {
        String buyer = requireSupportedBuyer(buyerCode);
        PrintRequest entity = requireRequest(buyer, requestId);
        assertRequestFactoryAccess(entity);
        String currentStatus = normalizeStatus(entity.getStatus());
        if (!AppConstants.PrintRequestStatus.SENT.equals(currentStatus) && !AppConstants.PrintRequestStatus.PENDING.equals(currentStatus)) {
            throw new WorkflowValidationException(AppMessages.HANDOFF_CANCEL_STATUS_INVALID);
        }
        User actor = factoryAccess.currentUser();
        if (!actor.isAdminRole() && !actor.canManageSales() && !RequestActor.current().equalsIgnoreCase(entity.getPackingEmail())) {
            throw new WorkflowValidationException(AppMessages.HANDOFF_CANCEL_PERMISSION);
        }
        entity.setStatus(AppConstants.PrintRequestStatus.CANCELLED);
        entity.setUpdatedAt(LocalDateTime.now());
        PrintRequest saved = repository.save(entity);
        auditLogService.log(RequestActor.current(), AppConstants.AuditAction.CANCEL_PO_HANDOFF, AppConstants.AuditResource.PO_HANDOFF, saved.getId(),
                "Cancelled PO handoff " + saved.getRequestNo(), null, null);
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.PO_HANDOFF_CANCELLED, saved.getId());
        return saved;
    }

    /**
     * Permanently removes a Print Request only before Print Room starts printing.
     * The request stores PO snapshots only, so deleting it intentionally leaves
     * PurchaseOrder, Carton, Item, SKU, SSCC and Packing data untouched. Once the
     * request document is removed, its POs are no longer matched by active-handoff
     * queries and can be selected for a new Print Request immediately.
     */
    public void delete(String buyerCode, String requestId) {
        String buyer = requireSupportedBuyer(buyerCode);
        PrintRequest entity = requireRequest(buyer, requestId);
        assertRequestFactoryAccess(entity);

        String currentStatus = normalizeStatus(entity.getStatus());
        boolean deletable = AppConstants.PrintRequestStatus.SENT.equals(currentStatus)
                || AppConstants.PrintRequestStatus.PENDING.equals(currentStatus);
        if (!deletable) {
            throw new WorkflowValidationException(AppMessages.HANDOFF_DELETE_STATUS_INVALID);
        }

        User actor = factoryAccess.currentUser();
        String actorEmail = RequestActor.current();
        if (!actor.isAdminRole() && !actor.canManageSales()
                && !actorEmail.equalsIgnoreCase(entity.getPackingEmail())) {
            throw new WorkflowValidationException(AppMessages.HANDOFF_DELETE_PERMISSION);
        }

        String requestNo = entity.getRequestNo();
        int poCount = entity.getPos() == null ? 0 : entity.getPos().size();
        String entityId = entity.getId();
        repository.delete(entity);

        auditLogService.log(actorEmail, AppConstants.AuditAction.DELETE_PO_HANDOFF,
                AppConstants.AuditResource.PO_HANDOFF, entityId,
                "Deleted Print Request " + requestNo + " before printing. " + poCount
                        + " PO(s) released for a new Print Request; Packing/Carton data retained.",
                null, null);
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.PO_HANDOFF_DELETED, entityId);
    }

    private void rejectAlreadyQueued(String buyer, List<String> poIds) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("buyerCode").is(buyer),
                Criteria.where("status").in(AppConstants.PrintRequestStatus.SENT, AppConstants.PrintRequestStatus.PENDING, AppConstants.PrintRequestStatus.PRINTING),
                Criteria.where("pos.poId").in(poIds)
        ));
        List<PrintRequest> existing = mongoTemplate.find(query, PrintRequest.class);
        if (!existing.isEmpty()) {
            Set<String> blocked = new LinkedHashSet<>();
            for (PrintRequest row : existing) {
                for (PrintRequestPo po : row.getPos()) {
                    if (poIds.contains(po.getPoId())) blocked.add(po.getPoNumber());
                }
            }
            throw new WorkflowValidationException(AppMessages.handoffPoAlreadyQueued(blocked));
        }
    }

    private String resolveDepartment(User user) {
        if (user == null || user.getDepartmentId() == null || user.getDepartmentId().isBlank()) return null;
        Department department = departmentRepository.findById(user.getDepartmentId()).orElse(null);
        if (department == null) return null;
        String division = clean(department.getDivision());
        String name = clean(department.getDepartmentName());
        if (division == null) return name;
        if (name == null) return division;
        return division + " / " + name;
    }

    /**
     * Older print requests have only Style in their saved PO snapshot. Backfill
     * Color/Size in API responses when the original scoped PO still exists,
     * without changing the saved handoff or replacing its historical values.
     */
    private void populateMissingPoAttributes(List<PrintRequest> requests) {
        List<String> ids = requests.stream()
                .filter(Objects::nonNull)
                .flatMap(request -> (request.getPos() == null ? List.<PrintRequestPo>of() : request.getPos()).stream())
                .filter(Objects::nonNull)
                .filter(item -> !hasText(item.getStyle()) || !hasText(item.getColor()) || !hasText(item.getSize()))
                .map(PrintRequestPo::getPoId)
                .filter(this::hasText)
                .distinct()
                .toList();
        if (ids.isEmpty()) return;

        Map<String, PurchaseOrder> posById = new HashMap<>();
        poRepository.findAllById(ids).forEach(po -> posById.put(po.getId(), po));
        for (PrintRequest request : requests) {
            if (request == null || request.getPos() == null) continue;
            for (PrintRequestPo item : request.getPos()) {
                if (item == null) continue;
                PurchaseOrder po = posById.get(item.getPoId());
                if (po == null || !Objects.equals(po.getOrderId(), request.getOrderId())
                        || !Objects.equals(po.getBuyerCode(), request.getBuyerCode())) continue;
                if (!hasText(item.getStyle())) item.setStyle(po.getStyle());
                if (!hasText(item.getColor())) item.setColor(po.getColor());
                if (!hasText(item.getSize())) item.setSize(po.getSize());
            }
        }
    }

    private PrintRequestPo snapshot(PurchaseOrder po) {
        return new PrintRequestPo(
                po.getId(), po.getPoNumber(), po.getFactoryCode(), po.getStyle(), po.getColor(), po.getSize(), po.getSku(),
                po.getPlannedCartons(), po.getPlannedTotalQty(), po.getStatus()
        );
    }

    private void assertRequestFactoryAccess(PrintRequest entity) {
        if (factoryAccess.isUnrestricted() || factoryAccess.currentUser().canPrintRoom()) return;
        // Unassigned Factory must never block the LULULEMON workflow.
        if (entity.getFactoryCodes() == null || entity.getFactoryCodes().isEmpty()) return;
        List<String> allowed = factoryAccess.accessibleFactories();
        boolean visible = entity.getFactoryCodes().stream().allMatch(allowed::contains);
        if (!visible) throw new WorkflowValidationException(AppMessages.HANDOFF_ACCESS_DENIED);
    }

    private PrintRequest requireRequest(String buyer, String requestId) {
        return repository.findByIdAndBuyerCode(required(requestId, AppMessages.required("PO handoff")), buyer)
                .orElseThrow(() -> new WorkflowNotFoundException(AppMessages.HANDOFF_NOT_FOUND));
    }

    private void assertFactoryAccessIfPresent(String factoryCode) {
        if (clean(factoryCode) != null) factoryAccess.assertFactoryAccess(factoryCode);
    }

    private String requireSupportedBuyer(String value) {
        String buyer = BuyerAccess.normalize(value);
        if (!AppConstants.Buyer.LULULEMON.equals(buyer)) throw new WorkflowValidationException(AppMessages.handoffOnlyFor(AppConstants.Buyer.LULULEMON));
        return buyer;
    }

    private String required(String value, String message) {
        String clean = clean(value);
        if (clean == null) throw new WorkflowValidationException(message);
        return clean;
    }

    private String generateRequestNo(LocalDateTime now) {
        return "PR-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase(Locale.ROOT);
    }

    private String normalizeStatus(String value) {
        String clean = clean(value);
        return clean == null ? "" : clean.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private String clean(String value) {
        if (value == null) return null;
        String clean = value.trim().replaceAll("\\s+", " ");
        return clean.isEmpty() ? null : clean;
    }

    private boolean hasText(String value) { return clean(value) != null; }
    private String escape(String value) { return java.util.regex.Pattern.quote(value.trim()); }
}
