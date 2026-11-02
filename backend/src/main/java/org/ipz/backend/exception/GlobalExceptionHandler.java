package org.ipz.backend.exception;

import lombok.extern.slf4j.Slf4j;
import org.ipz.backend.exception.dto.ErrorResponseDto;
import org.ipz.backend.exception.dto.SeatConflictErrorResponseDto;
import org.ipz.backend.exception.dto.ValidationErrorResponseDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleNotFoundException(NotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponseDto.builder()
                        .time(LocalDateTime.now())
                        .statusCode(String.valueOf(HttpStatus.NOT_FOUND.value()))
                        .errorMessage(List.of(ex.getMessage()))
                        .build());
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorResponseDto> handleAuthException(AuthException ex) {
        log.warn("Authentication error ({}): {}", ex.getStatus(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponseDto.builder()
                        .time(LocalDateTime.now())
                        .statusCode(String.valueOf(ex.getStatus().value()))
                        .errorMessage(List.of(ex.getMessage()))
                        .build());
    }

    @ExceptionHandler(SeatOccupiedException.class)
    public ResponseEntity<SeatConflictErrorResponseDto> handleSeatOccupiedException(SeatOccupiedException ex) {
        log.warn("Seat conflict: {} - {}", ex.getMessage(), ex.getConflictingSeats());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(SeatConflictErrorResponseDto.builder()
                        .error("SEAT_ALREADY_OCCUPIED")
                        .message(ex.getMessage())
                        .conflictingSeats(ex.getConflictingSeats())
                        .build());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponseDto> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        log.warn("Validation failed: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ValidationErrorResponseDto.builder()
                        .time(LocalDateTime.now())
                        .statusCode(String.valueOf(HttpStatus.BAD_REQUEST.value()))
                        .errors(errors)
                        .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleGenericException(Exception ex) {
        log.error("Unhandled exception occurred", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponseDto.builder()
                        .time(LocalDateTime.now())
                        .statusCode(String.valueOf(HttpStatus.INTERNAL_SERVER_ERROR.value()))
                        .errorMessage(List.of(ex.getMessage()))
                        .build());
    }
}
