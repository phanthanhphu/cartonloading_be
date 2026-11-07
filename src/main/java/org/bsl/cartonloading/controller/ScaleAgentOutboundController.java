package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.buyers.lululemon.agent.ScaleAgentBroker;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Machine-authenticated read-only job channel; no JWT, but every request checks an Agent-scoped secret. */
@RestController
@RequestMapping("/api/device-agents/v1/scale/{factory}")
public class ScaleAgentOutboundController {
    private final ScaleAgentBroker broker;
    public ScaleAgentOutboundController(ScaleAgentBroker broker) { this.broker = broker; }

    @GetMapping("/poll")
    public ScaleAgentBroker.Job poll(@PathVariable String factory,
            @RequestHeader("X-Scale-Agent-Id") String agentId,
            @RequestHeader("X-Scale-Agent-Token") String token,
            @RequestHeader("X-Scale-Agent-Instance-Id") String instanceId) {
        return broker.poll(factory, agentId, token, instanceId);
    }

    @PostMapping("/result")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void result(@PathVariable String factory,
            @RequestHeader("X-Scale-Agent-Id") String agentId,
            @RequestHeader("X-Scale-Agent-Token") String token,
            @RequestHeader("X-Scale-Agent-Instance-Id") String instanceId,
            @RequestBody ScaleAgentBroker.Result result) {
        broker.result(factory, agentId, token, instanceId, result);
    }
}
