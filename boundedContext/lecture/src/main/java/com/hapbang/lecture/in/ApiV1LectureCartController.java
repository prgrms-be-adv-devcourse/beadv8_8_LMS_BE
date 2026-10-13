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

import jakarta.validation.Valid;

/** 장바구니 API (설계 8.4). Swagger 명세는 {@link ApiV1LectureCartSpec} */
// TODO: 13장 3단계에서 구현한다.
// TODO(security): 유저·인플루언서·인증된 인플루언서만 허용한다(관리자 불가, 설계 9장). global SecurityConfig와 함께 추가한다.
@RestController
@RequestMapping("/api/v1/lectures/cart")
public class ApiV1LectureCartController implements ApiV1LectureCartSpec {

    @Override
    @GetMapping
    public CartResponse getCart(@LoginUserId Long userId) {
        throw NotImplemented.yet();
    }

    @Override
    @PostMapping("/items")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addItem(@LoginUserId Long userId, @Valid @RequestBody CartItemAddRequest request) {
        throw NotImplemented.yet();
    }

    @Override
    @DeleteMapping("/items/{lectureId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId) {
        throw NotImplemented.yet();
    }
}
