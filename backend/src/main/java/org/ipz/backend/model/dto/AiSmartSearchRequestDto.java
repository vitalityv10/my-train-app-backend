package org.ipz.backend.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record AiSmartSearchRequestDto(
        @Schema(description = "Natural language train search query",
                example = "Потяг до Одеси з вайфаєм до 500 грн")
        @NotBlank(message = "query must not be blank")
        @Size(max = 500, message = "query must not exceed 500 characters")
        String query
) {
}
