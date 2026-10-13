package com.hapbang.lecture.in;

import com.hapbang.lecture.in.dto.CartItemAddRequest;
import com.hapbang.lecture.in.dto.CartResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 장바구니 API Swagger 명세 (설계 8.4). 매핑은 {@link ApiV1LectureCartController} */
@Tag(name = "장바구니", description = "강의 담기·빼기·조회")
public interface ApiV1LectureCartSpec {

    @Operation(summary = "장바구니 조회", description = "담은 뒤 판매 중지·삭제된 강의는 구매 불가(purchasable=false)로 표시한다.")
    CartResponse getCart(Long userId);

    @Operation(summary = "장바구니 담기",
            description = "판매 중인 강의만. 본인 강의, 이미 보유 중인 강의, 이미 담은 강의는 담을 수 없다.")
    void addItem(Long userId, CartItemAddRequest request);

    @Operation(summary = "장바구니 빼기")
    void removeItem(Long userId, Long lectureId);
}
