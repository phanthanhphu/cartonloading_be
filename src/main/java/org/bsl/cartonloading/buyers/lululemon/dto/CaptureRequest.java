package org.bsl.cartonloading.buyers.lululemon.dto;

/** Request wire contract preserved from RfidPdaDeviceController. */
public record CaptureRequest(String stationCode, String sscc, String sessionId) { }
