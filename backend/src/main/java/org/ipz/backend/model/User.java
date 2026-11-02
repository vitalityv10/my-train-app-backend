package org.ipz.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private String firebaseUid;
    private String email;
    private String password;
    private String name;
    private String photoUrl;
    private String phone;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<Ticket> tickets;
}
