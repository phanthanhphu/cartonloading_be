package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.*;
import org.bsl.cartonloading.buyers.lululemon.repository.*;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.factory.FactoryCodes;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.UserRepository;
import org.bsl.cartonloading.service.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Adds station bindings without replacing existing device repositories or touching RFID capture. */
@Service
public class StationDeviceBindingService {
    private static final String BUYER = AppConstants.Buyer.LULULEMON;
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private final StationDeviceBindingRepository bindings;
    private final ConveyorPlcDeviceRepository conveyors;
    private final ScalePlcDeviceRepository scales;
    private final UserRepository users;
    private final SelectedFactoryScope selectedFactory;
    private final FactoryCatalogService factories;
    private final AuditLogService audit;

    public record BindingRequest(String factoryCode, String deviceCode, boolean active) { }
    public record DeviceView(String code, String name, String factoryCode, String stationCode, String type,
                             String mode, String protocol, boolean enabled, String commissioningStatus) { }
    public StationDeviceBindingService(StationDeviceBindingRepository bindings,
         ConveyorPlcDeviceRepository conveyors, ScalePlcDeviceRepository scales, UserRepository users,
         SelectedFactoryScope selectedFactory, FactoryCatalogService factories, AuditLogService audit) {
        this.bindings=bindings; this.conveyors=conveyors; this.scales=scales; this.users=users;
        this.selectedFactory=selectedFactory; this.factories=factories; this.audit=audit;
    }
    private static String key(String factory, String station, String role) {
        return BUYER + "|" + factory + "|" + station + "|" + role;
    }
    private static String normalize(String input) {
        return input == null ? "" : input.trim().toUpperCase(Locale.ROOT);
    }
    private User actor() {
        User user=users.findByEmail(RequestActor.current())
             .orElseThrow(()->new AccessDeniedException("Login required"));
        if (!user.isEnabled() || !user.canAccessBuyer(BUYER))
             throw new AccessDeniedException("Buyer access denied");
        return user;
    }
    private String factoryAccess(String factory) {
        String normalized=normalize(factory);
        if (!FactoryCodes.isValid(normalized)) throw new IllegalArgumentException("Invalid Factory");
        factories.requireActive(BUYER, normalized);
        User user=actor();
        if (!user.isAdminRole() && (!user.canLululemonScanWeight() || !user.canAccessFactory(normalized)))
             throw new AccessDeniedException("Factory access denied");
        selectedFactory.assertMatches(selectedFactory.requested(BUYER), normalized);
        return normalized;
    }
    public List<DeviceView> catalog() {
        User u=actor();
        String selected=selectedFactory.requested(BUYER);
        List<DeviceView> result=new ArrayList<>();
        for (ConveyorPlcDevice d:conveyors.findAll()) {
            if (!BUYER.equalsIgnoreCase(d.getBuyerKey()) || !selectedFactory.matches(selected,d.getFactoryCode())) continue;
            if (!u.isAdminRole() && (!u.canLululemonScanWeight() || !u.canAccessFactory(d.getFactoryCode())
                || d.getAssignedUserIds()==null || !d.getAssignedUserIds().contains(u.getId()))) continue;
            result.add(new DeviceView(d.getDeviceCode(), d.getName(), d.getFactoryCode(), d.getStationCode(),
                "CONVEYOR", d.getMode(), d.getProtocol(), d.isEnabled(), d.getCommissioningStatus()));
        }
        for (ScalePlcDevice d:scales.findAll()) {
            if (!BUYER.equalsIgnoreCase(d.getBuyerKey()) || !selectedFactory.matches(selected,d.getFactoryCode())) continue;
            if (!u.isAdminRole() && (!u.canLululemonScanWeight() || !u.canAccessFactory(d.getFactoryCode())
                || d.getAssignedUserIds()==null || !d.getAssignedUserIds().contains(u.getId()))) continue;
            String mode=ScalePlcDeviceService.SIMULATOR.equals(d.getConnectionType()) ? AppConstants.DeviceConnection.SIMULATOR : "REAL";
            result.add(new DeviceView(d.getStationCode(),d.getName(),d.getFactoryCode(),null,
                "SCALE",mode,d.getConnectionType(),d.isEnabled(),"EXISTING"));
        }
        result.sort(Comparator.comparing(DeviceView::type).thenComparing(DeviceView::code));
        return result;
    }
    public List<StationDeviceBinding> bindingsForStation(String factory, String station) {
        String normalizedFactory=factoryAccess(factory);
        if (!CODE.matcher(station).matches()) throw new IllegalArgumentException("Invalid Station");
        return bindings.findByFactoryCodeAndStationCode(normalizedFactory, station).stream()
            .filter(b->BUYER.equals(b.getBuyerCode())).sorted(Comparator.comparing(StationDeviceBinding::getDeviceRole)).toList();
    }
    public List<StationDeviceBinding> listAll() {
        User u=actor(); String selected=selectedFactory.requested(BUYER);
        return bindings.findAll().stream().filter(b->BUYER.equals(b.getBuyerCode()))
            .filter(b->selectedFactory.matches(selected,b.getFactoryCode()))
            .filter(b->u.isAdminRole() || (u.canLululemonScanWeight() && u.canAccessFactory(b.getFactoryCode())))
            .sorted(Comparator.comparing(StationDeviceBinding::getFactoryCode).thenComparing(StationDeviceBinding::getStationCode)
              .thenComparing(StationDeviceBinding::getDeviceRole)).toList();
    }
    public synchronized StationDeviceBinding assign(String factory, String station, String role, BindingRequest req) {
        if (!actor().isAdminRole()) throw new AccessDeniedException("Admin only");
        String f=factoryAccess(factory), r=normalize(role);
        if (!CODE.matcher(station).matches() || !List.of("CONVEYOR","SCALE").contains(r))
            throw new IllegalArgumentException("Only CONVEYOR and SCALE bindings are managed here; RFID simulator is unchanged");
        if (req==null || !f.equals(normalize(req.factoryCode())) || req.deviceCode()==null)
            throw new IllegalArgumentException("Factory/device mismatch");
        String code=req.deviceCode().trim();
        if (!CODE.matcher(code).matches()) throw new IllegalArgumentException("Invalid device code");
        if ("CONVEYOR".equals(r)) {
            ConveyorPlcDevice d=conveyors.findById(code).orElseThrow(()->new IllegalArgumentException("Conveyor not found"));
            if (!d.isEnabled() || !f.equals(d.getFactoryCode()) || !station.equals(d.getStationCode()))
                throw new IllegalArgumentException("Conveyor must be enabled and belong to the same Factory/Station");
        } else {
            ScalePlcDevice d=scales.findById(code).orElseThrow(()->new IllegalArgumentException("Scale not found"));
            if (!d.isEnabled() || !f.equals(d.getFactoryCode()))
                throw new IllegalArgumentException("Scale must be enabled and belong to the same Factory");
        }
        String bindingId=key(f,station,r);
        for (StationDeviceBinding existing:bindings.findAll()) {
            if (existing.isActive() && BUYER.equals(existing.getBuyerCode()) && r.equals(existing.getDeviceRole())
              && code.equals(existing.getDeviceCode()) && !bindingId.equals(existing.getId()))
                throw new IllegalArgumentException("Device is already assigned to another active Station");
        }
        java.util.Map<String, Object> before = audit.snapshot(bindings.findById(bindingId).orElse(null));
        StationDeviceBinding b=bindings.findById(bindingId).orElseGet(StationDeviceBinding::new);
        b.setId(bindingId);b.setBuyerCode(BUYER);b.setFactoryCode(f);b.setStationCode(station);
        b.setDeviceRole(r);b.setDeviceCode(code);b.setActive(req.active());
        b.setUpdatedAt(Instant.now());b.setUpdatedBy(RequestActor.current());
        StationDeviceBinding saved=bindings.save(b);
        audit.logChange(RequestActor.current(),"STATION_DEVICE_ASSIGNMENT",AppConstants.AuditResource.CARTON_LOADING,
             bindingId,"Device="+code+"; active="+req.active(),BUYER,f,before,saved,null,null);
        return saved;
    }
    public void unassign(String factory,String station,String role) {
        if (!actor().isAdminRole()) throw new AccessDeniedException("Admin only");
        String f=factoryAccess(factory),r=normalize(role);
        if (!CODE.matcher(station).matches() || !List.of("CONVEYOR","SCALE").contains(r))
            throw new IllegalArgumentException("Invalid Station/role");
        String id=key(f,station,r);
        StationDeviceBinding previous=bindings.findById(id).orElse(null);
        bindings.deleteById(id);
        audit.logChange(RequestActor.current(),"STATION_DEVICE_UNASSIGN",AppConstants.AuditResource.CARTON_LOADING,
            id,"Unassigned",BUYER,f,previous,null,null,null);
    }
}
