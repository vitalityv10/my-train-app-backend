package org.ipz.backend.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record AiChatRequestDto(
        @Schema(description = "Chat session ID (UUID). If omitted, a new session ID will be generated",
                example = "550e8400-e29b-41d4-a716-446655440000")
        String sessionId,

        @Schema(description = "User message to the AI route assistant",
                example = "Знайди потяг до Львова з Wi-Fi до 600 грн")
        @NotBlank(message = "message must not be blank")
        @Size(max = 1000, message = "message must not exceed 1000 characters")
        String message
) {
}
