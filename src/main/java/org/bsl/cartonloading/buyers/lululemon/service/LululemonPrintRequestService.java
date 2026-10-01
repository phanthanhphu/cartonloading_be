package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.bsl.cartonloading.buyers.es.service.PackingOrderService;
import org.bsl.cartonloading.buyers.lululemon.dto.LululemonPrintRequestCreateRequest;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonPo;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonPrintRequest;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonPrintRequestPo;
import org.bsl.cartonloading.buyers.lululemon.repository.LululemonPoRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.LululemonPrintRequestRepository;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class LululemonPrintRequestService {
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private final LululemonPrintRequestRepository repository;
    private final LululemonPoRepository poRepository;
    private final PackingOrderService orderService;
    private final BuyerFactoryAccessService factoryAccess;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final MongoTemplate mongoTemplate;
    private final AuditLogService auditLogService;
    private final AppSocketPublisher socketPublisher;

    public LululemonPrintRequestService(
            LululemonPrintRequestRepository repository,
            LululemonPoRepository poRepository,
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

    public LululemonPrintRequest create(String buyerCode, String orderId, LululemonPrintRequestCreateRequest request) {
        String buyer = requireLululemon(buyerCode);
        PackingOrder order = orderService.getEntity(buyer, required(orderId, "Order is required"));
        List<String> poIds = request == null || request.poIds() == null ? List.of() : request.poIds().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();

        if (poIds.isEmpty()) throw new WorkflowValidationException("Select at least one PO to send");
        if (poIds.size() > 200) throw new WorkflowValidationException("A PO handoff can contain at most 200 POs");

        List<LululemonPo> pos = new ArrayList<>();
        for (String poId : poIds) {
            LululemonPo po = poRepository.findByIdAndOrderIdAndBuyerCode(poId, orderId, buyer)
                    .orElseThrow(() -> new WorkflowValidationException("PO is not part of the selected Order: " + poId));
            factoryAccess.assertFactoryAccess(po.getFactoryCode());
            pos.add(po);
        }

        Set<String> requestFactories = pos.stream()
                .map(LululemonPo::getFactoryCode)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (requestFactories.size() > 1) {
            throw new WorkflowValidationException("Select POs from one Factory per handoff");
        }

        rejectAlreadyQueued(buyer, poIds);

        String actorEmail = RequestActor.current();
        User actor = userRepository.findByEmail(actorEmail).orElse(null);
        String packingUser = actor != null && actor.getUsername() != null && !actor.getUsername().isBlank()
                ? actor.getUsername().trim() : actorEmail;
        LocalDateTime now = LocalDateTime.now();

        LululemonPrintRequest entity = new LululemonPrintRequest();
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
        entity.setStatus(STATUS_SENT);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        LululemonPrintRequest saved = repository.save(entity);
        auditLogService.log(actorEmail, "SEND_PO_HANDOFF", "LULULEMON_PO_HANDOFF", saved.getId(),
                "Sent " + saved.getPoCount() + " PO(s) to Print Room. Handoff " + saved.getRequestNo(), null, null);
        socketPublisher.cartonLoadingChanged("PO_HANDOFF_SENT", saved.getId());
        return saved;
    }

    public Page<LululemonPrintRequest> list(
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
        String buyer = requireLululemon(buyerCode);
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
        // to a Packing Factory (F1-F7). This exception applies only to PO Handoff
        // read access; it does not grant Packing access to other factories.
        boolean printRoomViewer = factoryAccess.currentUser().canPrintRoom();
        if (!factoryAccess.isUnrestricted() && !printRoomViewer) {
            List<String> factories = factoryAccess.accessibleFactories();
            if (factories.isEmpty()) return new PageImpl<>(List.of(), pageable, 0);
            criteria.add(Criteria.where("factoryCodes").in(factories));
        }

        Query query = new Query();
        if (!criteria.isEmpty()) query.addCriteria(new Criteria().andOperator(criteria.toArray(Criteria[]::new)));
        long total = mongoTemplate.count(query, LululemonPrintRequest.class);
        query.with(pageable);
        List<LululemonPrintRequest> rows = mongoTemplate.find(query, LululemonPrintRequest.class);
        return new PageImpl<>(rows, pageable, total);
    }

    public LululemonPrintRequest get(String buyerCode, String requestId) {
        LululemonPrintRequest entity = requireRequest(requireLululemon(buyerCode), requestId);
        assertRequestFactoryAccess(entity);
        return entity;
    }

    public LululemonPrintRequest cancel(String buyerCode, String requestId) {
        String buyer = requireLululemon(buyerCode);
        LululemonPrintRequest entity = requireRequest(buyer, requestId);
        assertRequestFactoryAccess(entity);
        String currentStatus = normalizeStatus(entity.getStatus());
        if (!STATUS_SENT.equals(currentStatus) && !"PENDING".equals(currentStatus)) {
            throw new WorkflowValidationException("Only a sent PO handoff can be cancelled");
        }
        User actor = factoryAccess.currentUser();
        if (!actor.isAdminRole() && !actor.canManageSales() && !RequestActor.current().equalsIgnoreCase(entity.getPackingEmail())) {
            throw new WorkflowValidationException("Only the Packing sender, Sales, or Admin can cancel this request");
        }
        entity.setStatus(STATUS_CANCELLED);
        entity.setUpdatedAt(LocalDateTime.now());
        LululemonPrintRequest saved = repository.save(entity);
        auditLogService.log(RequestActor.current(), "CANCEL_PO_HANDOFF", "LULULEMON_PO_HANDOFF", saved.getId(),
                "Cancelled PO handoff " + saved.getRequestNo(), null, null);
        socketPublisher.cartonLoadingChanged("PO_HANDOFF_CANCELLED", saved.getId());
        return saved;
    }

    private void rejectAlreadyQueued(String buyer, List<String> poIds) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("buyerCode").is(buyer),
                Criteria.where("status").in(STATUS_SENT, "PENDING", "PRINTING"),
                Criteria.where("pos.poId").in(poIds)
        ));
        List<LululemonPrintRequest> existing = mongoTemplate.find(query, LululemonPrintRequest.class);
        if (!existing.isEmpty()) {
            Set<String> blocked = new LinkedHashSet<>();
            for (LululemonPrintRequest row : existing) {
                for (LululemonPrintRequestPo po : row.getPos()) {
                    if (poIds.contains(po.getPoId())) blocked.add(po.getPoNumber());
                }
            }
            throw new WorkflowValidationException("PO already exists in an active handoff: " + String.join(", ", blocked));
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

    private LululemonPrintRequestPo snapshot(LululemonPo po) {
        return new LululemonPrintRequestPo(
                po.getId(), po.getPoNumber(), po.getFactoryCode(), po.getStyle(), po.getSku(),
                po.getPlannedCartons(), po.getPlannedTotalQty(), po.getStatus()
        );
    }

    private void assertRequestFactoryAccess(LululemonPrintRequest entity) {
        if (factoryAccess.isUnrestricted() || factoryAccess.currentUser().canPrintRoom()) return;
        List<String> allowed = factoryAccess.accessibleFactories();
        boolean visible = entity.getFactoryCodes() != null && !entity.getFactoryCodes().isEmpty()
                && entity.getFactoryCodes().stream().allMatch(allowed::contains);
        if (!visible) throw new WorkflowValidationException("You do not have permission to access this PO handoff");
    }

    private LululemonPrintRequest requireRequest(String buyer, String requestId) {
        return repository.findByIdAndBuyerCode(required(requestId, "PO handoff is required"), buyer)
                .orElseThrow(() -> new WorkflowNotFoundException("PO handoff not found"));
    }

    private String requireLululemon(String value) {
        String buyer = BuyerAccess.normalize(value);
        if (!BuyerAccess.LULULEMON.equals(buyer)) throw new WorkflowValidationException("PO handoff is only available for LULULEMON");
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
