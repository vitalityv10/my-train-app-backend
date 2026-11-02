package org.ipz.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ipz.backend.exception.AuthException;
import org.ipz.backend.model.AuthResult;
import org.ipz.backend.model.User;
import org.ipz.backend.model.dto.UserLoginRequestDto;
import org.ipz.backend.model.dto.UserRegisterRequestDto;
import org.ipz.backend.model.entity.UserEntity;
import org.ipz.backend.repository.UserRepository;
import org.ipz.backend.service.AuthService;
import org.ipz.backend.service.mapper.UserServiceMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Transactional
@RequiredArgsConstructor
@Service
@Slf4j
public class AuthServiceImpl implements AuthService {

    private static final String FIREBASE_SIGN_UP_URL =
            "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=";
    private static final String FIREBASE_SIGN_IN_URL =
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=";

    private final UserRepository userRepository;
    private final UserServiceMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${app.firebase.api-key:}")
    private String firebaseApiKey;

    @Override
    public AuthResult register(UserRegisterRequestDto requestDto) {
        String email = requestDto.email().trim().toLowerCase();

        if (StringUtils.hasText(firebaseApiKey)) {
            JsonNode firebaseResp = callFirebaseAuth(FIREBASE_SIGN_UP_URL + firebaseApiKey, email, requestDto.password());
            String firebaseUid = firebaseResp.path("localId").asText();
            String idToken = firebaseResp.path("idToken").asText();

            UserEntity entity = userRepository.findById(firebaseUid)
                    .orElseGet(() -> UserEntity.builder()
                            .firebaseUid(firebaseUid)
                            .email(email)
                            .build());
            entity.setEmail(email);
            entity.setPassword(passwordEncoder.encode(requestDto.password()));
            if (StringUtils.hasText(requestDto.name())) {
                entity.setName(requestDto.name());
            }
            if (StringUtils.hasText(requestDto.phone())) {
                entity.setPhone(requestDto.phone());
            }
            if (StringUtils.hasText(requestDto.photo())) {
                entity.setPhotoUrl(requestDto.photo());
            }
            entity.setUpdatedAt(OffsetDateTime.now());

            UserEntity saved = userRepository.save(entity);
            log.info("Registered user via Firebase Auth with UID: {}", firebaseUid);
            return AuthResult.builder()
                    .user(userMapper.toDomain(saved))
                    .token(idToken)
                    .build();
        }

        if (userRepository.findByEmail(email).isPresent()) {
            throw new AuthException(HttpStatus.CONFLICT, "User with email " + email + " already exists");
        }

        String uid = UUID.randomUUID().toString();
        UserEntity entity = UserEntity.builder()
                .firebaseUid(uid)
                .email(email)
                .password(passwordEncoder.encode(requestDto.password()))
                .name(requestDto.name())
                .phone(requestDto.phone())
                .photoUrl(requestDto.photo())
                .build();

        UserEntity saved = userRepository.save(entity);
        String token = generateLocalJwt(saved);
        log.info("Registered user locally with UID: {}", uid);

        return AuthResult.builder()
                .user(userMapper.toDomain(saved))
                .token(token)
                .build();
    }

    @Override
    public AuthResult login(UserLoginRequestDto requestDto) {
        String email = requestDto.email().trim().toLowerCase();

        if (StringUtils.hasText(firebaseApiKey)) {
            JsonNode firebaseResp = callFirebaseAuth(FIREBASE_SIGN_IN_URL + firebaseApiKey, email, requestDto.password());
            String firebaseUid = firebaseResp.path("localId").asText();
            String idToken = firebaseResp.path("idToken").asText();
            String displayName = firebaseResp.path("displayName").asText(null);

            UserEntity entity = userRepository.findById(firebaseUid)
                    .or(() -> userRepository.findByEmail(email))
                    .orElseGet(() -> UserEntity.builder()
                            .firebaseUid(firebaseUid)
                            .email(email)
                            .name(displayName)
                            .build());
            entity.setUpdatedAt(OffsetDateTime.now());
            UserEntity saved = userRepository.save(entity);

            log.info("Authenticated user via Firebase Auth with UID: {}", saved.getFirebaseUid());
            return AuthResult.builder()
                    .user(userMapper.toDomain(saved))
                    .token(idToken)
                    .build();
        }

        UserEntity entity = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));

        if (!StringUtils.hasText(entity.getPassword()) || !passwordEncoder.matches(requestDto.password(), entity.getPassword())) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        String token = generateLocalJwt(entity);
        User user = userMapper.toDomain(entity);
        log.info("Authenticated user locally with UID: {}", entity.getFirebaseUid());

        return AuthResult.builder()
                .user(user)
                .token(token)
                .build();
    }

    private JsonNode callFirebaseAuth(String url, String email, String password) {
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "email", email,
                    "password", password,
                    "returnSecureToken", true
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());

            if (response.statusCode() >= 400) {
                String firebaseMessage = root.path("error").path("message").asText("Authentication failed");
                HttpStatus status = "EMAIL_EXISTS".equals(firebaseMessage) ? HttpStatus.CONFLICT : HttpStatus.UNAUTHORIZED;
                throw new AuthException(status, "Firebase Auth error: " + firebaseMessage);
            }

            return root;
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to communicate with Firebase Auth REST API", e);
            throw new AuthException(HttpStatus.BAD_GATEWAY, "Failed to communicate with Firebase Auth service");
        }
    }

    private String generateLocalJwt(UserEntity user) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer("my-train-app-backend")
                .issuedAt(now)
                .expiresAt(now.plus(24, ChronoUnit.HOURS))
                .subject(user.getFirebaseUid())
                .claim("email", user.getEmail());

        if (StringUtils.hasText(user.getName())) {
            claimsBuilder.claim("name", user.getName());
        }
        if (StringUtils.hasText(user.getPhotoUrl())) {
            claimsBuilder.claim("picture", user.getPhotoUrl());
        }

        return jwtEncoder.encode(JwtEncoderParameters.from(claimsBuilder.build())).getTokenValue();
    }
}
