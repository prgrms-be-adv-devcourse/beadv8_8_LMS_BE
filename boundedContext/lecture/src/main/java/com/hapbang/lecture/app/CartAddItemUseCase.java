package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.domain.Cart;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.out.CartRepository;

import lombok.RequiredArgsConstructor;

/** 장바구니 담기. 장바구니는 처음 담을 때 만든다 */
@Service
@RequiredArgsConstructor
public class CartAddItemUseCase {

    private final CartRepository cartRepository;
    private final LectureSupport lectureSupport;

    public void addItem(Long userId, Long lectureId) {
        LocalDateTime now = LocalDateTime.now();
        Lecture lecture = lectureSupport.findLecture(lectureId);
        Cart cart = findOrCreateCart(userId, now);
        cart.add(lecture, now);
        // 같은 강의를 동시에 담은 요청이 먼저 커밋했으면 부분 유일 인덱스에 걸린다. 커밋 전에 확인해 409로 바꾼다
        try {
            cartRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new LectureException(LectureErrorCode.ALREADY_IN_CART);
        }
    }

    private Cart findOrCreateCart(Long userId, LocalDateTime now) {
        return cartRepository.findByUserId(userId).orElseGet(() -> {
            cartRepository.insertIfAbsent(userId, now);
            return cartRepository.findByUserId(userId).orElseThrow();
        });
    }
}
