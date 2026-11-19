package org.bsl.cartonloading.dto.admin;

import jakarta.validation.constraints.NotNull;

/** Strict status-only request; Factory code and name are never part of this update. */
public record FactoryStatusRequest(@NotNull Boolean active) { }
