package org.bsl.cartonloading.model;

import java.util.List;

/** Explicit per-Buyer workflow grants for a non-admin user. */
public record BuyerTypePermission(String buyerCode, List<String> types) { }
