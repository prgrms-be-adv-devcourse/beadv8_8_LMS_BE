package com.hapbang.lecture.in.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "장바구니 주문은 담은 강의 ID들, 바로 구매는 강의 ID 1개")
public record OrderCreateRequest(
        // 요청 하나로 HOLD 작업·지갑 호출을 무제한 만들지 못하게 한다. 요청 수 한도(D80)와 함께 쓴다
        @Schema(description = "주문할 강의 ID. 1~100개")
        @NotEmpty @Size(max = MAX_LECTURES) List<@NotNull Long> lectureIds
) {

    /** 한 주문에 담을 수 있는 강의 수 (D80). 장바구니에는 상한이 없다 */
    public static final int MAX_LECTURES = 100;
}
