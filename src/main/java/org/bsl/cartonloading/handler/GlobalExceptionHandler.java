package org.bsl.cartonloading.handler;

import org.bsl.cartonloading.common.constants.AppMessages;
import jakarta.validation.ConstraintViolationException;
import org.bsl.cartonloading.common.importing.ImportValidationException;
import org.bsl.cartonloading.common.exception.WorkflowNotFoundException;
import org.bsl.cartonloading.common.exception.WorkflowValidationException;
import org.bsl.cartonloading.dto.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({ImportValidationException.class, WorkflowValidationException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiError> handleBadRequest(RuntimeException ex) {
        logger.warn("Bad request: {}", ex.getMessage());
        return json(HttpStatus.BAD_REQUEST, new ApiError(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
    }


    /** Authorization failures are not server bugs. Do not mask them as generic 500s. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        logger.warn("Access denied: {}", ex.getMessage());
        return json(HttpStatus.FORBIDDEN,
                new ApiError(HttpStatus.FORBIDDEN.value(), ex.getMessage() == null
                        ? "Access denied." : ex.getMessage()));
    }

    /** Concurrent/duplicate SSCC or item assignment must not appear as a server crash. */
    @ExceptionHandler({DuplicateKeyException.class, DataIntegrityViolationException.class})
    public ResponseEntity<ApiError> handleDataConflict(RuntimeException ex) {
        logger.warn("Data conflict while saving: {}", ex.getMessage());
        return json(HttpStatus.CONFLICT,
                new ApiError(HttpStatus.CONFLICT.value(),
                        "Record conflict (possibly an SSCC already assigned). Refresh the PO and check its cartons before retrying."));
    }

    @ExceptionHandler(WorkflowNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(WorkflowNotFoundException ex) {
        return json(HttpStatus.NOT_FOUND, new ApiError(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
    }

    /** Missing API/static paths must be HTTP 404, not an unexpected HTTP 500. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResourceFound(NoResourceFoundException ex) {
        logger.debug("Resource not found: {}", ex.getMessage());
        return json(HttpStatus.NOT_FOUND,
                new ApiError(HttpStatus.NOT_FOUND.value(), "Resource not found."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        ApiError body = new ApiError(HttpStatus.BAD_REQUEST.value(), AppMessages.VALIDATION_FAILED);
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fields.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        body.setFieldErrors(fields);
        return json(HttpStatus.BAD_REQUEST, body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex) {
        return json(HttpStatus.BAD_REQUEST, new ApiError(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUpload(MaxUploadSizeExceededException ex) {
        return json(HttpStatus.PAYLOAD_TOO_LARGE, new ApiError(HttpStatus.PAYLOAD_TOO_LARGE.value(), AppMessages.UPLOADED_EXCEL_TOO_LARGE));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex) {
        logger.debug("Client disconnected before response completed: {}", ex.getMessage());
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiError> handleIOException(IOException ex) throws IOException {
        if (isClientAbort(ex)) {
            logger.debug("Client aborted connection before response completed: {}", ex.getMessage());
            return null;
        }
        logger.error("I/O error: {}", ex.getMessage(), ex);
        return json(HttpStatus.INTERNAL_SERVER_ERROR, new ApiError(HttpStatus.INTERNAL_SERVER_ERROR.value(), AppMessages.IO_ERROR_OCCURRED));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleAllExceptions(Exception ex) {
        if (isClientAbort(ex)) {
            logger.debug("Client aborted connection before response completed: {}", ex.getMessage());
            return null;
        }
        logger.error("Unexpected error: {}", ex.getMessage(), ex);
        return json(HttpStatus.INTERNAL_SERVER_ERROR, new ApiError(HttpStatus.INTERNAL_SERVER_ERROR.value(), AppMessages.UNEXPECTED_ERROR_OCCURRED));
    }

    private ResponseEntity<ApiError> json(HttpStatus status, ApiError error) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(error);
    }

    private boolean isClientAbort(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage();
            String normalizedMessage = message == null ? "" : message.toLowerCase();
            if (className.contains("ClientAbortException")
                    || current instanceof AsyncRequestNotUsableException
                    || normalizedMessage.contains("broken pipe")
                    || normalizedMessage.contains("connection reset")
                    || normalizedMessage.contains("connection was aborted")
                    || normalizedMessage.contains("failed to flush")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
