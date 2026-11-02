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
import org.ipz.backend.model.User;
import org.ipz.backend.model.dto.UserResponseDto;
import org.ipz.backend.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User Profile",
        description = "Current user profile and purchased tickets operations")
@ApiResponses(value = {
        @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing Firebase ID Token",
                content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
        @ApiResponse(responseCode = "500", description = "Internal server error",
                content = @Content(schema = @Schema(implementation = ErrorResponseDto.class)))
})
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserService userService;
    private final UserControllerMapper mapper;

    @Operation(summary = "Get current user profile and tickets",
            description = "Returns the authenticated user's profile and their purchased train tickets")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "404", description = "User not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "200", description = "Successfully fetched user profile and tickets")
    })
    @GetMapping("/me")
    public ResponseEntity<UserResponseDto> getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        String firebaseUid = jwt.getSubject();
        log.info("Received request to fetch profile and tickets for user: {}", firebaseUid);

        User user = userService.getUserWithTickets(firebaseUid);
        UserResponseDto response = mapper.toDto(user);

        return ResponseEntity.ok(response);
    }
}
