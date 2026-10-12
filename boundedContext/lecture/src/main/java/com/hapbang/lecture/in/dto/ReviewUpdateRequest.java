package com.hapbang.lecture.in.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReviewUpdateRequest(
        @NotNull @Min(1) @Max(5) Integer rating,
        String content
) {
}
