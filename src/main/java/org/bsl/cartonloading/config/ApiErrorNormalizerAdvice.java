package org.bsl.cartonloading.config;

import org.bsl.cartonloading.support.ApiErrorPayloadNormalizer;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring MVC replacement for the advice reported in the compiler log.
 * Normalizes the shared ApiError response envelope.
 * Does not change successful responses, downloads, strings, or HTTP status.
 */
@RestControllerAdvice(basePackages = "org.bsl.cartonloading")
public class ApiErrorNormalizerAdvice implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return MappingJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || !supports(returnType, selectedConverterType)
                || !isJson(selectedContentType)
                || !(response instanceof ServletServerHttpResponse)) {
            return body;
        }
        ServletServerHttpResponse servlet = (ServletServerHttpResponse) response;
        if (servlet.getServletResponse().isCommitted()) return body;
        int status = servlet.getServletResponse().getStatus();
        if (status < 400 || status > 599) return body;

        Map<String, Object> payload = asPayload(body);
        if (payload == null) return body;
        HttpStatus knownStatus = HttpStatus.resolve(status);
        return ApiErrorPayloadNormalizer.normalize(payload, status,
                knownStatus == null ? "Request failed" : knownStatus.getReasonPhrase());
    }

    private boolean isJson(MediaType type) {
        return type != null && "application".equalsIgnoreCase(type.getType())
                && ("json".equalsIgnoreCase(type.getSubtype())
                || type.getSubtype().toLowerCase(java.util.Locale.ROOT).endsWith("+json"));
    }

    private Map<String, Object> asPayload(Object body) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (body instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) body).entrySet()) {
                if (!(entry.getKey() instanceof String)) return null;
                result.put((String) entry.getKey(), entry.getValue());
            }
            return result;
        }
        if (body instanceof org.bsl.cartonloading.dto.ApiError) {
            org.bsl.cartonloading.dto.ApiError error =
                    (org.bsl.cartonloading.dto.ApiError) body;
            result.put("status", error.getStatus());
            result.put("message", error.getMessage());
            result.put("timestamp", error.getTimestamp());
            result.put("fieldErrors", error.getFieldErrors());
            return result;
        }
        return null;
    }
}
