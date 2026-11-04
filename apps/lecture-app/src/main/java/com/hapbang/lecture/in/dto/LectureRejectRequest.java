package com.hapbang.lecture.in.dto;

import jakarta.validation.constraints.NotBlank;

public record LectureRejectRequest(
        @NotBlank String reason
) {
}
