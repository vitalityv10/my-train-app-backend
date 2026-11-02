package org.ipz.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ipz.backend.controller.mapper.CheckoutControllerMapper;
import org.ipz.backend.exception.dto.ErrorResponseDto;
import org.ipz.backend.exception.dto.SeatConflictErrorResponseDto;
import org.ipz.backend.exception.dto.ValidationErrorResponseDto;
import org.ipz.backend.model.CheckoutPurchase;
import org.ipz.backend.model.Ticket;
import org.ipz.backend.model.dto.CheckoutPurchaseRequestDto;
import org.ipz.backend.model.dto.CheckoutPurchaseResponseDto;
import org.ipz.backend.service.CheckoutService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Checkout & Tickets",
        description = "Atomic seat booking, ticket purchase, and occupancy update")
@ApiResponses(value = {
        @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing Firebase ID Token",
                content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
        @ApiResponse(responseCode = "500", description = "Internal server error",
                content = @Content(schema = @Schema(implementation = ErrorResponseDto.class)))
})
@RestController
@RequestMapping("/api/checkout")
@RequiredArgsConstructor
@Slf4j
public class CheckoutController {

    private final CheckoutService checkoutService;
    private final CheckoutControllerMapper mapper;

    @Operation(summary = "Purchase tickets",
            description = "Executes seat booking, payment recording, Redis occupancy update, and cart clearing in a single atomic transaction")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ValidationErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Train not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "409", description = "Conflict - One or more requested seats are already occupied",
                    content = @Content(schema = @Schema(implementation = SeatConflictErrorResponseDto.class))),
            @ApiResponse(responseCode = "201", description = "Tickets purchased successfully")
    })
    @PostMapping("/purchase")
    public ResponseEntity<CheckoutPurchaseResponseDto> purchaseTickets(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CheckoutPurchaseRequestDto requestDto) {

        String firebaseUid = jwt.getSubject();
        String userEmail = jwt.getClaimAsString("email");
        String userName = jwt.getClaimAsString("name");

        log.info("Received ticket purchase request from user {} for {} tickets",
                firebaseUid, requestDto.tickets().size());

        CheckoutPurchase domainRequest = mapper.toDomain(requestDto);
        List<Ticket> purchasedTickets = checkoutService.purchaseTickets(firebaseUid, userEmail, userName, domainRequest);

        CheckoutPurchaseResponseDto response = CheckoutPurchaseResponseDto.builder()
                .message("Tickets purchased successfully")
                .tickets(mapper.toPurchasedTicketDtoList(purchasedTickets))
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
