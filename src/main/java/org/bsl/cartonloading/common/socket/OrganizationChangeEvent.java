package org.bsl.cartonloading.common.socket;

import java.time.Instant;

/** Invalidation notice only: clients reload authorized HTTP resources. No PII or row identifiers. */
public record OrganizationChangeEvent(String module, String action, Instant at) { }
