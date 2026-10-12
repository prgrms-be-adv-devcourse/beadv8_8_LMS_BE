package com.hapbang.lecture.in.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

@Schema(description = "장바구니 주문은 담은 강의 ID들, 바로 구매는 강의 ID 1개")
public record OrderCreateRequest(
        @NotEmpty List<@NotNull Long> lectureIds
) {
}
