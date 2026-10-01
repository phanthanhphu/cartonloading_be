package org.bsl.cartonloading.handler;

import jakarta.validation.ConstraintViolationException;
import org.bsl.cartonloading.common.importing.ImportValidationException;
import org.bsl.cartonloading.common.exception.WorkflowNotFoundException;
import org.bsl.cartonloading.common.exception.WorkflowValidationException;
import org.bsl.cartonloading.dto.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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


    @ExceptionHandler(WorkflowNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(WorkflowNotFoundException ex) {
        return json(HttpStatus.NOT_FOUND, new ApiError(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        ApiError body = new ApiError(HttpStatus.BAD_REQUEST.value(), "Validation failed");
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
        return json(HttpStatus.PAYLOAD_TOO_LARGE, new ApiError(HttpStatus.PAYLOAD_TOO_LARGE.value(), "Uploaded Excel file is too large"));
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
        return json(HttpStatus.INTERNAL_SERVER_ERROR, new ApiError(HttpStatus.INTERNAL_SERVER_ERROR.value(), "I/O error occurred"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleAllExceptions(Exception ex) {
        if (isClientAbort(ex)) {
            logger.debug("Client aborted connection before response completed: {}", ex.getMessage());
            return null;
        }
        logger.error("Unexpected error: {}", ex.getMessage(), ex);
        return json(HttpStatus.INTERNAL_SERVER_ERROR, new ApiError(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Unexpected error occurred"));
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
