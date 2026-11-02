package com.hapbang.shared.customersupport.event;

import org.springframework.modulith.events.Externalized;

/**
 * 구매자가 판단 전 이의 제기 문의를 삭제했다(철회 요청, #279, D52·D60 (2)·D71).
 * 강의가 받아 항목을 접수 전 상태로 되돌리고 결과 이벤트를 낸다. 철회 명령 하나에 한 번 발행된다.
 *
 * @param commandId   철회 명령 ID ({@code WITHDRAW:<문의 ID>})
 * @param inquiryId   이의 제기 문의 ID
 * @param orderItemId 이의 제기한 주문 항목 ID
 * @param buyerUserId 문의를 쓴 구매자 회원 ID
 */
@Externalized(CustomerSupportEventTopic.NAME + "::#{'inquiry:' + #this.inquiryId()}")
public record LectureObjectionWithdrawalRequestedEvent(String commandId, Long inquiryId, Long orderItemId,
                                                       Long buyerUserId) {
}
