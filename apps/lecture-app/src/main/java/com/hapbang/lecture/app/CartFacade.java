package com.hapbang.lecture.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.app.dto.CartResponse;

import lombok.RequiredArgsConstructor;

/**
 * 장바구니 진입점 (설계 8.4). 유저·인플루언서·인증된 인플루언서만 쓴다. 관리자는 불가 (설계 9장, D16).
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('USER', 'INFLUENCER', 'VERIFIED_INFLUENCER')")
public class CartFacade {

    private final CartAddItemUseCase cartAddItemUseCase;
    private final CartRemoveItemUseCase cartRemoveItemUseCase;
    private final CartSupport cartSupport;

    /** 장바구니 조회 */
    @Transactional(readOnly = true)
    public CartResponse getCart(Long userId) {
        return cartSupport.getCart(userId);
    }

    /** 장바구니 담기 */
    @Transactional
    public void addItem(Long userId, Long lectureId) {
        cartAddItemUseCase.addItem(userId, lectureId);
    }

    /** 장바구니 빼기 */
    @Transactional
    public void removeItem(Long userId, Long lectureId) {
        cartRemoveItemUseCase.removeItem(userId, lectureId);
    }
}
