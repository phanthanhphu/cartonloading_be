package org.bsl.cartonloading.buyers.lululemon.dto;
import org.bsl.cartonloading.buyers.lululemon.service.RfidPdaDeviceService;

/** Request wire contract preserved from RfidPdaManagementController. */
public record RebindRequest(String newSerial, String factoryCode, RfidPdaDeviceService.GlobalDeviceSettings settings) { }
