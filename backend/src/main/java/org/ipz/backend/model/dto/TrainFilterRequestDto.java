package org.ipz.backend.model.dto;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TrainFilterRequestDto(

        @Parameter(description = "Search by train number, departure city, or arrival city")
        String search,

        @Parameter(description = "Filter by departure or arrival city")
        String city,

        @Parameter(description = "Filter by departure date (YYYY-MM-DD)")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate date,

        @Parameter(description = "Maximum ticket base price")
        @DecimalMin(value = "0.0", message = "maxPrice must be non-negative")
        BigDecimal maxPrice,

        @Parameter(description = "Filter only trains with Wi-Fi")
        Boolean wifiOnly,

        @Parameter(description = "Page number (1-based)")
        @Min(value = 1, message = "page must be at least 1")
        Integer page,

        @Parameter(description = "Items per page")
        @Min(value = 1, message = "limit must be at least 1")
        Integer limit
) {
}
