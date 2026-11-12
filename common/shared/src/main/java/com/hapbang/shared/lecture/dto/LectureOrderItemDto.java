package com.hapbang.shared.lecture.dto;

/**
 * 주문 이벤트에 싣는 주문 항목 하나 (#872, D85). 결제 완료·결제 실패 이벤트가 주문의 모든 항목을 이 형태로 싣는다.
 * 알림은 주문 항목 ID와 구매자 ID로 항목마다 구매 취소 마감 임박 예약을 만든다.
 *
 * @param orderItemId   주문 항목 ID
 * @param lectureId     강의 ID
 * @param lectureTitle  주문할 때의 강의 제목. 주문 뒤 강의 제목이 바뀌어도 그대로다
 * @param buyerAmountHp 구매자가 내는 HP = 가격 − 쿠폰 할인. 보류한(또는 보류하려던) 금액이고 0일 수 있다
 */
public record LectureOrderItemDto(Long orderItemId, Long lectureId, String lectureTitle, Long buyerAmountHp) {
}
