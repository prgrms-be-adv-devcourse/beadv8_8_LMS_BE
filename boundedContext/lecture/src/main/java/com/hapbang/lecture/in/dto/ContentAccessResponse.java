package com.hapbang.lecture.in.dto;

import java.time.LocalDateTime;

public record ContentAccessResponse(
        String url,
        LocalDateTime expiresAt
) {
}
