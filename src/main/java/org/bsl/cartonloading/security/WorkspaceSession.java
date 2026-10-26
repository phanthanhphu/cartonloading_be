package org.bsl.cartonloading.security;

/** Immutable server-validated Buyer/Type identity attached to each authenticated request. */
public record WorkspaceSession(String buyer, String type) { }
