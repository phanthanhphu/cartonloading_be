package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.buyers.lululemon.model.StationDeviceBinding;
import org.bsl.cartonloading.buyers.lululemon.service.StationDeviceBindingService;
import org.bsl.cartonloading.service.facade.StationDeviceBindingFacade;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Shared controller; Buyer dispatch remains in facade. No changes to RFID simulator. */
@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/station-devices")
public class StationDeviceBindingController {
    private final StationDeviceBindingFacade facade;
    public StationDeviceBindingController(StationDeviceBindingFacade facade) {this.facade=facade;}

    @GetMapping("/catalog")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public List<StationDeviceBindingService.DeviceView> catalog(@PathVariable String buyer) {
        return facade.forBuyer(buyer, svc->svc.catalog());
    }
    @GetMapping("/bindings")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public List<StationDeviceBinding> bindings(@PathVariable String buyer) {
        return facade.forBuyer(buyer, svc->svc.listAll());
    }
    @GetMapping("/{factory}/{station}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public List<StationDeviceBinding> forStation(@PathVariable String buyer,@PathVariable String factory,@PathVariable String station) {
        return facade.forBuyer(buyer,svc->svc.bindingsForStation(factory,station));
    }
    @PutMapping("/{factory}/{station}/{role}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public StationDeviceBinding assign(@PathVariable String buyer,@PathVariable String factory,@PathVariable String station,
          @PathVariable String role,@RequestBody StationDeviceBindingService.BindingRequest request) {
        return facade.forBuyer(buyer,svc->svc.assign(factory,station,role,request));
    }
    @DeleteMapping("/{factory}/{station}/{role}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public void unassign(@PathVariable String buyer,@PathVariable String factory,@PathVariable String station,@PathVariable String role) {
        facade.forBuyer(buyer,svc->{svc.unassign(factory,station,role);return null;});
    }
}
