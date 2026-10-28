package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.hapbang.lecture.out.CartRepository;

import lombok.RequiredArgsConstructor;

/** 장바구니 빼기 (Soft Delete). 담지 않은 강의거나 장바구니가 없으면 아무것도 하지 않는다 (D18) */
@Service
@RequiredArgsConstructor
public class CartRemoveItemUseCase {

    private final CartRepository cartRepository;

    public void removeItem(Long userId, Long lectureId) {
        cartRepository.findByUserId(userId)
                .ifPresent(cart -> cart.remove(lectureId, LocalDateTime.now()));
    }
}
