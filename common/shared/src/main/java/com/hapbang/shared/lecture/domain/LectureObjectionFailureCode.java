package com.hapbang.shared.lecture.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * 강의가 이의 제기 명령을 거절한 사유 (#278, D71). 접수 HTTP 오류 응답의 {@code code}와 거절 이벤트의 {@code failureCode}가
 * 같은 이름을 쓴다. 강의 오류 코드({@code LectureErrorCode.OBJECTION_*})와 이름이 같다. 상수 이름은 응답·이벤트 문자열이므로 바꾸지 않는다.
 */
public enum LectureObjectionFailureCode {
    /** 접수·철회: 구매자 본인의 주문 항목이 아니다 (없는 항목 포함) */
    OBJECTION_NOT_BUYER,
    /** 접수: 구매 취소되었거나 반환된 항목이다 (처리 중 포함) */
    OBJECTION_NOT_OBJECTIONABLE,
    /** 접수: 이미 판매자에게 지급된 항목이다 */
    OBJECTION_ALREADY_PAID_OUT,
    /** 접수: 이미 이의 제기가 접수되었거나 판단된 항목이다 */
    OBJECTION_ALREADY_FILED,
    /** 접수: 이의 제기 기간(지급 예정 시각)이 지났다 */
    OBJECTION_PERIOD_EXPIRED,
    /** 판단·철회: 판단을 기다리는 이의 제기가 없다 (판단은 없는 항목 포함) */
    OBJECTION_NOT_FILED,
    /** 판단·철회: 접수할 때의 문의와 다른 문의다 */
    OBJECTION_INQUIRY_MISMATCH,
    /** 같은 명령 ID로 다른 내용이 왔다. 먼저 처리한 결과는 바뀌지 않는다 */
    OBJECTION_COMMAND_CONFLICT;

    /** 오류 응답의 {@code code} 문자열에 맞는 사유. 이 계약에 없는 코드(내부 API 키 오류 등)면 비어 있다 */
    public static Optional<LectureObjectionFailureCode> from(String code) {
        return Arrays.stream(values()).filter(value -> value.name().equals(code)).findFirst();
    }
}
