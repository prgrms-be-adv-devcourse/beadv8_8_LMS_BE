package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Cart;
import com.hapbang.lecture.domain.CartItem;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.out.CartRepository;

/** 장바구니 담기. 장바구니는 처음 담을 때 만든다 */
@Service
public class CartAddItemUseCase {

    /** 장바구니에 살아 있는 같은 강의를 하나만 두는 부분 유일 인덱스 (D18) */
    private static final String CART_LECTURE_INDEX = "uk_cart_item_cart_lecture";

    private final CartRepository cartRepository;
    private final LectureSupport lectureSupport;
    private final LectureSyncEvents lectureSyncEvents;
    private final Clock clock;

    public CartAddItemUseCase(CartRepository cartRepository, LectureSupport lectureSupport,
                              LectureSyncEvents lectureSyncEvents, @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.cartRepository = cartRepository;
        this.lectureSupport = lectureSupport;
        this.lectureSyncEvents = lectureSyncEvents;
        this.clock = clock;
    }

    public void addItem(Long userId, Long lectureId) {
        // 담은 시각은 동기화 이벤트(#712)에 실리므로 강의 시계(KST, 마이크로초)로 정한다
        LocalDateTime now = LocalDateTime.now(clock);
        Lecture lecture = lectureSupport.findLecture(lectureId);
        Cart cart = findOrCreateCart(userId, now);
        CartItem added = cart.add(lecture, now);
        // 같은 강의를 동시에 담은 요청이 먼저 커밋했으면 부분 유일 인덱스에 걸린다. 커밋 전에 확인해 409로 바꾼다
        try {
            cartRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            if (!UniqueIndexViolation.isOf(exception, CART_LECTURE_INDEX)) {
                throw exception;
            }
            throw new LectureException(LectureErrorCode.ALREADY_IN_CART);
        }
        lectureSyncEvents.cartItemChanged(added);
    }

    private Cart findOrCreateCart(Long userId, LocalDateTime now) {
        return cartRepository.findByUserId(userId).orElseGet(() -> {
            cartRepository.insertIfAbsent(userId, now);
            return cartRepository.findByUserId(userId).orElseThrow();
        });
    }
}
