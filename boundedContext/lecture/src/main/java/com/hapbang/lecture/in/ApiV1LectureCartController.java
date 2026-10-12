package com.hapbang.lecture.in;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.global.auth.LoginUserId;
import com.hapbang.lecture.in.dto.CartItemAddRequest;
import com.hapbang.lecture.in.dto.CartResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** 장바구니 API (설계 8.4) */
// TODO: 13장 3단계에서 구현한다.
// TODO(security): 유저·인플루언서·인증된 인플루언서만 허용한다(관리자 불가, 설계 9장). global SecurityConfig와 함께 추가한다.
@Tag(name = "장바구니", description = "강의 담기·빼기·조회")
@RestController
@RequestMapping("/api/v1/lectures/cart")
public class ApiV1LectureCartController {

    @Operation(summary = "장바구니 조회", description = "담은 뒤 판매 중지·삭제된 강의는 구매 불가(purchasable=false)로 표시한다.")
    @GetMapping
    public CartResponse getCart(@LoginUserId Long userId) {
        throw NotImplemented.yet();
    }

    @Operation(summary = "장바구니 담기",
            description = "판매 중인 강의만. 본인 강의, 이미 보유 중인 강의, 이미 담은 강의는 담을 수 없다.")
    @PostMapping("/items")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addItem(@LoginUserId Long userId, @Valid @RequestBody CartItemAddRequest request) {
        throw NotImplemented.yet();
    }

    @Operation(summary = "장바구니 빼기")
    @DeleteMapping("/items/{lectureId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId) {
        throw NotImplemented.yet();
    }
}
