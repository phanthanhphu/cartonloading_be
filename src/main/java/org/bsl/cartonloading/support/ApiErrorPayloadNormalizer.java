package org.bsl.cartonloading.support;

import org.bsl.cartonloading.common.enums.ApiErrorCode;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Preserves legacy error details while adding missing envelope metadata. */
public final class ApiErrorPayloadNormalizer {
    private ApiErrorPayloadNormalizer() {}

    public static Map<String, Object> normalize(Map<String, ?> source, int status,
                                                String defaultMessage) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        // The actual HTTP response is authoritative, including non-enum codes.
        result.put("status", status);
        Object message = result.get("message");
        if (message == null || (message instanceof String && ((String) message).isBlank())) {
            Object detail = result.get("detail");
            result.put("message", detail instanceof String && !((String) detail).isBlank()
                    ? detail : defaultMessage);
        }
        result.putIfAbsent("timestamp", LocalDateTime.now());
        Object code = result.get("errorCode");
        if (code == null || (code instanceof String && ((String) code).isBlank())) {
            Object fields = result.get("fieldErrors");
            boolean validation = (status == 400 || status == 422)
                    && fields instanceof Map && !((Map<?, ?>) fields).isEmpty();
            result.put("errorCode", validation ? ApiErrorCode.VALIDATION_FAILED.name() : codeFor(status));
        }
        return result;
    }

    private static String codeFor(int status) {
        switch (status) {
            case 400: return ApiErrorCode.BAD_REQUEST.name();
            case 401: return ApiErrorCode.UNAUTHORIZED.name();
            case 403: return ApiErrorCode.FORBIDDEN.name();
            case 404: return ApiErrorCode.NOT_FOUND.name();
            case 405: return ApiErrorCode.METHOD_NOT_ALLOWED.name();
            case 409: return ApiErrorCode.CONFLICT.name();
            case 413: return ApiErrorCode.PAYLOAD_TOO_LARGE.name();
            case 415: return ApiErrorCode.UNSUPPORTED_MEDIA_TYPE.name();
            case 422: return ApiErrorCode.UNPROCESSABLE_ENTITY.name();
            case 429: return ApiErrorCode.TOO_MANY_REQUESTS.name();
            default: return status >= 500 ? ApiErrorCode.INTERNAL_SERVER_ERROR.name() : ApiErrorCode.REQUEST_FAILED.name();
        }
    }
}
