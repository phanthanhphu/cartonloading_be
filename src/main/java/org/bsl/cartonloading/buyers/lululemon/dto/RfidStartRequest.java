package org.bsl.cartonloading.buyers.lululemon.dto;

/** SSCC is used as physical carton identity; never infer a carton from PO alone. */
public record RfidStartRequest(String sscc, String stationCode) { }
