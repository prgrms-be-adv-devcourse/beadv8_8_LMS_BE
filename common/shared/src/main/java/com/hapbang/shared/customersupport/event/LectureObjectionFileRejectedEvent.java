package com.hapbang.shared.customersupport.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * 강의 내용 다름 이의 제기를 접수할 수 없게 됐다(재시도 배치가 보낸 접수를 강의가 거절함). 알림센터가 구매자에게 알린다(알림 계약 M7).
 * 문의마다 한 번 발행된다. 회원 요청 안에서 바로 거절된 접수는 화면에 바로 보이므로 내지 않는다.
 *
 * @param inquiryId      이의 제기 문의 ID
 * @param orderItemId    이의 제기한 주문 항목 ID
 * @param buyerUserId    문의를 쓴 구매자 회원 ID(알림 받는 사람)
 * @param fileRejectedAt 접수 불가 시각
 */
@Externalized(CustomerSupportEventTopic.NAME + "::#{'inquiry:' + #this.inquiryId()}")
public record LectureObjectionFileRejectedEvent(Long inquiryId, Long orderItemId, Long buyerUserId,
                                                Instant fileRejectedAt) {
}
