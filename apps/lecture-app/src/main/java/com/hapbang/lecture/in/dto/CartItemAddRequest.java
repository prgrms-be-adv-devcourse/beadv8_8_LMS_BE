package com.hapbang.lecture.in.dto;

import jakarta.validation.constraints.NotNull;

public record CartItemAddRequest(
        @NotNull Long lectureId
) {
}
