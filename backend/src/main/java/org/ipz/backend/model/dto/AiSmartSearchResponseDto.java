package org.ipz.backend.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

@Builder
public record AiSmartSearchResponseDto(
        @Schema(description = "Structured filter extracted from natural language query")
        TrainFilter appliedFilter,

        @Schema(description = "List of trains matching the extracted filter")
        List<TrainResponseDto> trains
) {
}
