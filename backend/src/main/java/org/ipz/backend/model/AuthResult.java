package org.ipz.backend.model;

import lombok.Builder;

@Builder
public record AuthResult(
        User user,
        String token
) {
}
