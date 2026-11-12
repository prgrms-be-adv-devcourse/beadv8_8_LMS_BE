package com.hapbang.shared.customersupport.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * 관리자가 문의에 답변을 달았다. 알림센터가 문의한 회원에게 답변 도착을 알린다(알림 계약 M7).
 * 답변 하나에 한 번 발행된다. 문의 하나에 답변이 여러 개 달릴 수 있어 사건은 답변 ID로 구분한다.
 * 이의 제기 판단 안내 답변은 내지 않는다(판단 알림은 강의의 {@code LectureObjectionResolvedEvent}로 간다). 답변 수정도 내지 않는다.
 *
 * @param inquiryId  문의 ID
 * @param replyId    답변 ID
 * @param userId     문의한 회원 ID(알림 받는 사람)
 * @param title      문의 제목
 * @param category   문의 유형(고객센터 {@code InquiryCategory}의 이름, 예: {@code SERVICE_COMPLAINT})
 * @param answeredAt 답변 시각(답변 작성 시각)
 */
@Externalized(CustomerSupportEventTopic.NAME + "::#{'inquiry:' + #this.inquiryId()}")
public record InquiryAnsweredEvent(Long inquiryId, Long replyId, Long userId, String title, String category,
                                   Instant answeredAt) {
}
