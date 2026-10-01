package org.bsl.cartonloading.buyers.es.dto;

/** PASS/FAIL is an explicit manual packing inspection, not a fabricated scale value. */
public record CartonInspectionRequest(String result, String note) { }
