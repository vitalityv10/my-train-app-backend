package org.ipz.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ipz.backend.exception.dto.ErrorResponseDto;
import org.ipz.backend.exception.dto.ValidationErrorResponseDto;
import org.ipz.backend.model.dto.AiChatRequestDto;
import org.ipz.backend.model.dto.AiChatResponseDto;
import org.ipz.backend.model.dto.AiSmartSearchRequestDto;
import org.ipz.backend.model.dto.AiSmartSearchResponseDto;
import org.ipz.backend.service.AiAssistantService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AI Route Assistant",
        description = "AI-powered train route search assistant with tool calling and Redis session memory")
@ApiResponses(value = {
        @ApiResponse(responseCode = "429", description = "AI provider rate limit exceeded",
                content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
        @ApiResponse(responseCode = "503", description = "AI service temporarily unavailable or not configured",
                content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
        @ApiResponse(responseCode = "500", description = "Internal server error",
                content = @Content(schema = @Schema(implementation = ErrorResponseDto.class)))
})
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Slf4j
public class AiController {

    private final AiAssistantService aiAssistantService;

    @Operation(summary = "Chat with AI train route assistant",
            description = "Sends a message to the AI assistant. Uses tool calling to search trains, check available cities, "
                    + "inspect seat occupancy, and (if authenticated with Bearer JWT) view user's purchased tickets, "
                    + "manage shopping cart, and reserve tickets without entering card details.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ValidationErrorResponseDto.class))),
            @ApiResponse(responseCode = "200", description = "Successfully generated AI response and suggested trains")
    })
    @SecurityRequirements()
    @PostMapping("/chat")
    public ResponseEntity<AiChatResponseDto> chat(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AiChatRequestDto requestDto) {
        String firebaseUid = jwt != null ? jwt.getSubject() : null;
        String userEmail = jwt != null ? jwt.getClaimAsString("email") : null;
        String userName = jwt != null ? jwt.getClaimAsString("name") : null;
        log.info("Received AI chat request (sessionId={}, authenticated={})",
                requestDto.sessionId(), firebaseUid != null);

        AiChatResponseDto response = aiAssistantService.chat(requestDto, firebaseUid, userEmail, userName);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Clear AI chat session history",
            description = "Deletes the conversation history stored in Redis for the specified sessionId")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Chat session history cleared")
    })
    @SecurityRequirements()
    @DeleteMapping("/chat/{sessionId}")
    public ResponseEntity<Void> clearChatHistory(@PathVariable String sessionId) {
        log.info("Received request to clear AI chat history for session: {}", sessionId);

        aiAssistantService.clearChatHistory(sessionId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Smart natural language train search",
            description = "Extracts structured train filter parameters from a free-text query using AI and returns matching trains")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ValidationErrorResponseDto.class))),
            @ApiResponse(responseCode = "200", description = "Successfully parsed query and fetched matching trains")
    })
    @SecurityRequirements()
    @PostMapping("/smart-search")
    public ResponseEntity<AiSmartSearchResponseDto> smartSearch(
            @Valid @RequestBody AiSmartSearchRequestDto requestDto) {
        log.info("Received AI smart-search query: '{}'", requestDto.query());

        AiSmartSearchResponseDto response = aiAssistantService.smartSearch(requestDto);
        return ResponseEntity.ok(response);
    }
}
