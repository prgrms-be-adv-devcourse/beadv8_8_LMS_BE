package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.out.CartRepository;

/**
 * 장바구니 빼기 (Soft Delete). 담지 않은 강의거나 장바구니가 없으면 아무것도 하지 않는다 (D18).
 * 장바구니 행을 잠그고 읽어, 같은 항목을 동시에 빼도 뒤 요청은 이미 빠진 것을 보고 아무것도 하지 않는다(버전 충돌 없음, D82)
 */
@Service
public class CartRemoveItemUseCase {

    private final CartRepository cartRepository;
    private final LectureSyncEvents lectureSyncEvents;
    private final Clock clock;

    public CartRemoveItemUseCase(CartRepository cartRepository, LectureSyncEvents lectureSyncEvents,
                                 @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.cartRepository = cartRepository;
        this.lectureSyncEvents = lectureSyncEvents;
        this.clock = clock;
    }

    public void removeItem(Long userId, Long lectureId) {
        cartRepository.findForUpdateByUserId(userId)
                .flatMap(cart -> cart.remove(lectureId, LocalDateTime.now(clock)))
                .ifPresent(lectureSyncEvents::cartItemChanged);
    }
}
