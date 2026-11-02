package org.ipz.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ipz.backend.controller.mapper.UserControllerMapper;
import org.ipz.backend.exception.dto.ErrorResponseDto;
import org.ipz.backend.exception.dto.ValidationErrorResponseDto;
import org.ipz.backend.model.AuthResult;
import org.ipz.backend.model.User;
import org.ipz.backend.model.dto.UserLoginRequestDto;
import org.ipz.backend.model.dto.UserRegisterRequestDto;
import org.ipz.backend.model.dto.UserSyncRequestDto;
import org.ipz.backend.model.dto.UserSyncResponseDto;
import org.ipz.backend.service.AuthService;
import org.ipz.backend.service.UserService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication",
        description = "Firebase/JWT authentication, registration, login, and user synchronization")
@ApiResponses(value = {
        @ApiResponse(responseCode = "500", description = "Internal server error",
                content = @Content(schema = @Schema(implementation = ErrorResponseDto.class)))
})
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final UserControllerMapper mapper;

    @Operation(summary = "Register a new user",
            description = "Registers a user via Firebase Auth (or locally if FIREBASE_API_KEY is not set), saves the user in PostgreSQL, and returns the JWT in the Authorization header")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ValidationErrorResponseDto.class))),
            @ApiResponse(responseCode = "409", description = "User with this email already exists",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "201", description = "User registered successfully",
                    headers = @Header(name = HttpHeaders.AUTHORIZATION, description = "Bearer <JWT token>", schema = @Schema(type = "string")))
    })
    @SecurityRequirements()
    @PostMapping("/register")
    public ResponseEntity<UserSyncResponseDto> register(
            @Valid @RequestBody UserRegisterRequestDto requestDto) {
        log.info("Received registration request for email: {}", requestDto.email());

        AuthResult result = authService.register(requestDto);
        UserSyncResponseDto response = mapper.toSyncDto(result.user());

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + result.token())
                .body(response);
    }

    @Operation(summary = "Login with email and password",
            description = "Authenticates a user via Firebase Auth (or locally if FIREBASE_API_KEY is not set) and returns the JWT in the Authorization header")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ValidationErrorResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Invalid email or password",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "200", description = "Authenticated successfully",
                    headers = @Header(name = HttpHeaders.AUTHORIZATION, description = "Bearer <JWT token>", schema = @Schema(type = "string")))
    })
    @SecurityRequirements()
    @PostMapping("/login")
    public ResponseEntity<UserSyncResponseDto> login(
            @Valid @RequestBody UserLoginRequestDto requestDto) {
        log.info("Received login request for email: {}", requestDto.email());

        AuthResult result = authService.login(requestDto);
        UserSyncResponseDto response = mapper.toSyncDto(result.user());

        return ResponseEntity.ok()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + result.token())
                .body(response);
    }

    @Operation(summary = "Synchronize Firebase / Google OAuth2 user",
            description = "Verifies the Firebase ID token (e.g. from GoogleAuthProvider on the frontend), creates or updates the user record in PostgreSQL, and echoes the token in the Authorization header")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ValidationErrorResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing token",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDto.class))),
            @ApiResponse(responseCode = "200", description = "User synchronized successfully",
                    headers = @Header(name = HttpHeaders.AUTHORIZATION, description = "Bearer <JWT token>", schema = @Schema(type = "string")))
    })
    @PostMapping("/sync")
    public ResponseEntity<UserSyncResponseDto> syncUser(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody(required = false) UserSyncRequestDto requestDto) {

        String firebaseUid = jwt.getSubject();
        String tokenEmail = jwt.getClaimAsString("email");
        String tokenName = jwt.getClaimAsString("name");
        String tokenPicture = jwt.getClaimAsString("picture");

        log.info("Received user sync request for UID: {}", firebaseUid);

        User requestDomain = requestDto != null ? mapper.toDomain(requestDto) : null;
        User syncedUser = userService.syncUser(firebaseUid, tokenEmail, tokenName, tokenPicture, requestDomain);
        UserSyncResponseDto response = mapper.toSyncDto(syncedUser);

        return ResponseEntity.ok()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getTokenValue())
                .body(response);
    }
}
