package org.bsl.cartonloading.common.enums;

/** Stable API/database codes. Use name() at existing String boundaries. */
public enum ApiErrorCode {
    VALIDATION_FAILED,
    BAD_REQUEST,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    METHOD_NOT_ALLOWED,
    CONFLICT,
    PAYLOAD_TOO_LARGE,
    UNSUPPORTED_MEDIA_TYPE,
    UNPROCESSABLE_ENTITY,
    TOO_MANY_REQUESTS,
    INTERNAL_SERVER_ERROR,
    REQUEST_FAILED
}
