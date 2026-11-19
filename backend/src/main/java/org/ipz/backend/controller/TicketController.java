package org.ipz.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ipz.backend.controller.mapper.UserControllerMapper;
import org.ipz.backend.exception.dto.ErrorResponseDto;
import org.ipz.backend.model.Ticket;
import org.ipz.backend.model.dto.UserResponseDto;
import org.ipz.backend.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Tickets",
        description = "User purchased train tickets operations")
@ApiResponses(value = {
        @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing Firebase ID Token",
                content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
        @ApiResponse(responseCode = "500", description = "Internal server error",
                content = @Content(schema = @Schema(implementation = ErrorResponseDto.class)))
})
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
@Slf4j
public class TicketController {

    private final UserService userService;
    private final UserControllerMapper mapper;

    @Operation(summary = "Get current user's tickets",
            description = "Returns the authenticated user's purchased train tickets ordered by creation date descending")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully fetched user's tickets")
    })
    @GetMapping("/my")
    public ResponseEntity<List<UserResponseDto.UserTicketDto>> getMyTickets(@AuthenticationPrincipal Jwt jwt) {
        String firebaseUid = jwt.getSubject();
        log.info("Received request to fetch tickets for user: {}", firebaseUid);

        List<Ticket> tickets = userService.getUserTickets(firebaseUid);
        List<UserResponseDto.UserTicketDto> response = mapper.toTicketDtoList(tickets);

        return ResponseEntity.ok(response);
    }
}
