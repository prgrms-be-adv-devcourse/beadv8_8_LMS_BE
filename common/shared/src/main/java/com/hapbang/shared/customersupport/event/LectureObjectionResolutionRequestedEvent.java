package com.hapbang.shared.customersupport.event;

import org.springframework.modulith.events.Externalized;

/**
 * 관리자가 강의 내용 다름 이의 제기를 판단했다(고객센터에 판단 요청이 접수됨, #279, D60 (2)·D71).
 * 강의가 받아 항목에 반영하고 결과 이벤트를 낸다. 판단 명령 하나에 한 번 발행된다(재발행되면 강의가 명령 ID로 거른다).
 *
 * @param commandId   판단 명령 ID ({@code RESOLVE:<문의 ID>})
 * @param inquiryId   이의 제기 문의 ID
 * @param orderItemId 이의 제기한 주문 항목 ID
 * @param accepted    인정이면 true(구매자에게 HP 반환), 기각이면 false(판매자에게 지급)
 * @param adminUserId 판단한 관리자 회원 ID
 */
@Externalized(CustomerSupportEventTopic.NAME + "::#{'inquiry:' + #this.inquiryId()}")
public record LectureObjectionResolutionRequestedEvent(String commandId, Long inquiryId, Long orderItemId,
                                                       boolean accepted, Long adminUserId) {
}
