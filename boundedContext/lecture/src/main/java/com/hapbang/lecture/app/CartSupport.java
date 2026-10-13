package com.hapbang.lecture.app;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.hapbang.lecture.app.dto.CartItemResponse;
import com.hapbang.lecture.app.dto.CartResponse;
import com.hapbang.lecture.domain.CartItem;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.out.CartRepository;
import com.hapbang.lecture.out.LectureRepository;

import lombok.RequiredArgsConstructor;

/** 장바구니 조회. 응답 DTO 변환은 호출한 Facade의 트랜잭션 안에서 끝난다. */
@Service
@RequiredArgsConstructor
public class CartSupport {

    private final CartRepository cartRepository;
    private final LectureRepository lectureRepository;

    /**
     * 최근 담은 순. 담은 뒤 판매 중지·삭제된 강의도 보여 주되 구매 불가로 표시하고 합계에서 뺀다.
     * 장바구니가 아직 없으면(한 번도 담지 않았으면) 빈 장바구니로 응답한다.
     */
    public CartResponse getCart(Long userId) {
        List<Long> lectureIds = cartRepository.findByUserId(userId)
                .map(cart -> cart.getItems().stream().map(CartItem::getLectureId).toList())
                .orElse(List.of());
        // 삭제된 강의도 행이 남아 있으므로 함께 읽힌다
        Map<Long, Lecture> lectures = lectureRepository.findAllById(lectureIds).stream()
                .collect(Collectors.toMap(Lecture::getId, Function.identity()));
        List<CartItemResponse> items = lectureIds.stream()
                .map(lectures::get)
                .filter(Objects::nonNull)
                .map(CartItemResponse::of)
                .toList();
        return CartResponse.of(items);
    }
}
