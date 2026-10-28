package com.hapbang.lecture.in;

import org.springframework.http.ResponseEntity;

import com.hapbang.lecture.global.response.RsData;
import com.hapbang.lecture.app.dto.CartResponse;
import com.hapbang.lecture.in.dto.CartItemAddRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 장바구니 API Swagger 명세 (설계 8.4). 매핑은 {@link ApiV1LectureCartController} */
@Tag(name = "장바구니", description = "강의 담기·빼기·조회")
public interface ApiV1LectureCartSpec {

    @Operation(summary = "장바구니 조회",
            description = "최근 담은 순. 담은 뒤 판매 중지·삭제된 강의는 구매 불가(purchasable=false)로 표시하고 합계에서 뺀다.")
    RsData<CartResponse> getCart(Long userId);

    @Operation(summary = "장바구니 담기",
            description = "판매 중인 강의만(아니면 404 LECTURE_NOT_FOUND). 본인 강의(403 OWN_LECTURE_NOT_PURCHASABLE), "
                    + "이미 담은 강의(409 ALREADY_IN_CART)는 담을 수 없다. 이미 보유 중인 강의 거부는 주문 구현 때 추가한다. 담으면 204.")
    ResponseEntity<Void> addItem(Long userId, CartItemAddRequest request);

    @Operation(summary = "장바구니 빼기", description = "담지 않은 강의를 빼도 204로 응답한다.")
    ResponseEntity<Void> removeItem(Long userId, Long lectureId);
}
