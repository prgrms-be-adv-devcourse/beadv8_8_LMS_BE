package com.hapbang.shared.lecture.event;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.lecture.dto.CartItemSyncDto;

/**
 * 장바구니 항목이 바뀌었다 (#712, D82). 담기, 빼기, 결제가 끝나 장바구니에서 자동으로 빼기 뒤의 전체 상태를 싣는다.
 * key는 {@code cart-item:<장바구니 항목 ID>}다.
 *
 * @param cartItem 바뀐 뒤의 장바구니 항목
 */
@Externalized(LectureEventTopic.NAME + "::#{'cart-item:' + #this.cartItem().id()}")
public record CartItemChangedEvent(CartItemSyncDto cartItem) {
}
