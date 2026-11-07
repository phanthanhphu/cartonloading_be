package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.buyers.lululemon.agent.ScaleAgentBroker;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.facade.ScalePlcDeviceFacade;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Admin-only Factory enrollment and agent status. No secrets in GET responses. */
@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/scale-agents")
public class ScaleAgentController {
    private final ScaleAgentBroker broker;
    private final ScalePlcDeviceFacade facade;
    public ScaleAgentController(ScaleAgentBroker broker, ScalePlcDeviceFacade facade) {
        this.broker = broker; this.facade = facade;
    }
    private void checkBuyer(String buyer) {
        // Same buyer-scoped facade mechanism as existing Scale controllers.
        facade.forBuyer(buyer, service -> Boolean.TRUE);
    }
    @GetMapping("/{factory}/status")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ScaleAgentBroker.AgentStatus status(@PathVariable String buyer, @PathVariable String factory) {
        checkBuyer(buyer);
        return broker.status(factory);
    }
    @PostMapping("/{factory}/enroll")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ScaleAgentBroker.Enrollment enroll(@PathVariable String buyer, @PathVariable String factory) {
        checkBuyer(buyer);
        return broker.enroll(factory);
    }
}
