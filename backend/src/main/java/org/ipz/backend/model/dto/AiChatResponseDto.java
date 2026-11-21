package org.ipz.backend.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

@Builder
public record AiChatResponseDto(
        @Schema(description = "Active chat session ID",
                example = "550e8400-e29b-41d4-a716-446655440000")
        String sessionId,

        @Schema(description = "AI assistant reply text (Markdown supported)")
        String reply,

        @Schema(description = "Trains found during tool execution for rendering interactive cards")
        List<TrainResponseDto> suggestedTrains,

        @Schema(description = "Names of tools invoked by the AI assistant during this turn")
        List<String> usedTools
) {
}
