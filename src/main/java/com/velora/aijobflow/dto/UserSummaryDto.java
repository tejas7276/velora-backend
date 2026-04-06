package com.velora.aijobflow.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserSummaryDto {

    private Long          id;
    private String        name;
    private String        email;
    private String        role;
    private LocalDateTime createdAt;

    /** Convenience factory — converts User entity to safe DTO. */
    public static UserSummaryDto from(com.velora.aijobflow.model.User user) {
        return new UserSummaryDto(
            user.getId(),
            user.getName(),
            user.getEmail(),
            user.getRole(),
            user.getCreatedAt()
        );
    }
}