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
import org.ipz.backend.controller.mapper.CartControllerMapper;
import org.ipz.backend.exception.dto.ErrorResponseDto;
import org.ipz.backend.exception.dto.ValidationErrorResponseDto;
import org.ipz.backend.model.Cart;
import org.ipz.backend.model.dto.CartDto;
import org.ipz.backend.model.dto.CartItemAddRequestDto;
import org.ipz.backend.model.dto.CartItemUpdateRequestDto;
import org.ipz.backend.service.CartService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Shopping Cart",
        description = "Redis-backed user shopping cart operations")
@ApiResponses(value = {
        @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing Firebase ID Token",
                content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
        @ApiResponse(responseCode = "500", description = "Internal server error",
                content = @Content(schema = @Schema(implementation = ErrorResponseDto.class)))
})
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
@Slf4j
public class CartController {

    private final CartService cartService;
    private final CartControllerMapper mapper;

    @Operation(summary = "Get user's cart",
            description = "Fetches the authenticated user's shopping cart from Redis")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully fetched shopping cart")
    })
    @GetMapping
    public ResponseEntity<CartDto> getCart(@AuthenticationPrincipal Jwt jwt) {
        String firebaseUid = jwt.getSubject();
        log.debug("Fetching cart for user: {}", firebaseUid);

        Cart cart = cartService.getCart(firebaseUid);
        return ResponseEntity.ok(mapper.toDto(cart));
    }

    @Operation(summary = "Replace user's cart",
            description = "Replaces the entire shopping cart object in Redis for the authenticated user")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ValidationErrorResponseDto.class))),
            @ApiResponse(responseCode = "200", description = "Successfully updated shopping cart")
    })
    @PutMapping
    public ResponseEntity<CartDto> replaceCart(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CartDto requestDto) {
        String firebaseUid = jwt.getSubject();
        log.info("Replacing cart for user: {}", firebaseUid);

        Cart domainCart = mapper.toDomain(requestDto);
        Cart updated = cartService.replaceCart(firebaseUid, domainCart);
        return ResponseEntity.ok(mapper.toDto(updated));
    }

    @Operation(summary = "Add train to cart",
            description = "Adds a train to the user's cart or increments quantity if it already exists")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ValidationErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Train not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "200", description = "Item added to cart")
    })
    @PostMapping("/items")
    public ResponseEntity<CartDto> addItemToCart(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CartItemAddRequestDto requestDto) {
        String firebaseUid = jwt.getSubject();
        log.info("Adding train {} (qty: {}) to cart for user {}", requestDto.trainId(), requestDto.quantity(), firebaseUid);

        Cart updated = cartService.addItem(firebaseUid, requestDto.trainId(), requestDto.quantity());
        return ResponseEntity.ok(mapper.toDto(updated));
    }

    @Operation(summary = "Update cart item quantity",
            description = "Updates the quantity of a specific train item in the user's cart")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ValidationErrorResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Item not found in cart",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "200", description = "Cart item quantity updated")
    })
    @PatchMapping("/items/{trainId}")
    public ResponseEntity<CartDto> updateItemQuantity(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Integer trainId,
            @Valid @RequestBody CartItemUpdateRequestDto requestDto) {
        String firebaseUid = jwt.getSubject();
        log.info("Updating quantity of train {} to {} in cart for user {}", trainId, requestDto.quantity(), firebaseUid);

        Cart updated = cartService.updateItemQuantity(firebaseUid, trainId, requestDto.quantity());
        return ResponseEntity.ok(mapper.toDto(updated));
    }

    @Operation(summary = "Remove train from cart",
            description = "Removes a train item from the user's cart")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Item removed from cart")
    })
    @DeleteMapping("/items/{trainId}")
    public ResponseEntity<CartDto> removeItemFromCart(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Integer trainId) {
        String firebaseUid = jwt.getSubject();
        log.info("Removing train {} from cart for user {}", trainId, firebaseUid);

        Cart updated = cartService.removeItem(firebaseUid, trainId);
        return ResponseEntity.ok(mapper.toDto(updated));
    }

    @Operation(summary = "Clear entire cart",
            description = "Clears all items from the user's shopping cart in Redis")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Cart cleared successfully")
    })
    @DeleteMapping
    public ResponseEntity<Void> clearCart(@AuthenticationPrincipal Jwt jwt) {
        String firebaseUid = jwt.getSubject();
        log.info("Clearing cart for user {}", firebaseUid);

        cartService.clearCart(firebaseUid);
        return ResponseEntity.noContent().build();
    }
}
