package org.ipz.backend.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record CartItemAddRequestDto(
        @NotNull(message = "trainId is required")
        Integer trainId,

        @Min(value = 1, message = "quantity must be at least 1")
        Integer quantity
) {
}
