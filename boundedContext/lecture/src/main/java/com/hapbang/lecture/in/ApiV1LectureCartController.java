package com.hapbang.lecture.in;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.global.auth.LoginUserId;
import com.hapbang.global.response.RsData;
import com.hapbang.lecture.app.CartFacade;
import com.hapbang.lecture.app.dto.CartResponse;
import com.hapbang.lecture.in.dto.CartItemAddRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 장바구니 API (설계 8.4). Swagger 명세는 {@link ApiV1LectureCartSpec} */
// 성공은 global RsData로 감싸고(본문 없는 응답은 204), 오류는 global 공통 핸들러가 ProblemDetail로 응답한다 (D29).
// 역할 검사(설계 9장): 유저·인플루언서·인증된 인플루언서만, 관리자 불가 (CartFacade, D16).
@RestController
@RequestMapping("/api/v1/lectures/cart")
@RequiredArgsConstructor
public class ApiV1LectureCartController implements ApiV1LectureCartSpec {

    private final CartFacade cartFacade;

    @Override
    @GetMapping
    public RsData<CartResponse> getCart(@LoginUserId Long userId) {
        return RsData.ok("장바구니를 조회했습니다.", cartFacade.getCart(userId));
    }

    @Override
    @PostMapping("/items")
    public ResponseEntity<Void> addItem(@LoginUserId Long userId, @Valid @RequestBody CartItemAddRequest request) {
        cartFacade.addItem(userId, request.lectureId());
        return ResponseEntity.noContent().build();
    }

    @Override
    @DeleteMapping("/items/{lectureId}")
    public ResponseEntity<Void> removeItem(@LoginUserId Long userId, @PathVariable("lectureId") Long lectureId) {
        cartFacade.removeItem(userId, lectureId);
        return ResponseEntity.noContent().build();
    }
}
