package com.hapbang.shared.lecture.dto;

import java.util.Objects;

/**
 * 이의 제기 접수 성공 응답 (#278). 같은 명령을 다시 보내도 같은 값이다.
 *
 * @param commandId   처리한 명령 ID
 * @param inquiryId   이의 제기 문의 ID
 * @param orderItemId 접수된 주문 항목 ID. 이 항목은 판단·철회 전까지 지급·구매 취소되지 않는다
 */
public record LectureObjectionFiledDto(String commandId, Long inquiryId, Long orderItemId) {

    // 빠진 응답은 Client가 읽을 수 없는 응답으로 거절한다
    public LectureObjectionFiledDto {
        Objects.requireNonNull(commandId, "commandId는 필수입니다.");
        Objects.requireNonNull(inquiryId, "inquiryId는 필수입니다.");
        Objects.requireNonNull(orderItemId, "orderItemId는 필수입니다.");
    }
}
