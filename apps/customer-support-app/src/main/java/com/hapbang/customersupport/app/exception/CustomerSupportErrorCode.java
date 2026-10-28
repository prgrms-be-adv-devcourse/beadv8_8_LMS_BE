package com.hapbang.customersupport.app.exception;

import org.springframework.http.HttpStatus;

import com.hapbang.customersupport.global.exception.BaseErrorCode;
import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 고객센터 오류 코드. 응답의 code는 enum 이름이다. global의 공통 핸들러가 ProblemDetail로 응답한다 */
@Getter
@RequiredArgsConstructor
public enum CustomerSupportErrorCode implements BaseErrorCode {
    INQUIRY_NOT_FOUND(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다."),
    INQUIRY_NOT_DELETABLE(HttpStatus.CONFLICT, "답변이 달린 문의는 삭제할 수 없습니다."),
    INQUIRY_REPLY_NOT_FOUND(HttpStatus.NOT_FOUND, "답변을 찾을 수 없습니다."),
    INQUIRY_TARGET_REQUIRED(HttpStatus.BAD_REQUEST, "강의 내용 다름 이의 제기는 주문 항목 ID가 필요합니다."),
    INQUIRY_TARGET_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "주문 항목 ID는 강의 내용 다름 이의 제기에만 보낼 수 있습니다."),
    NOT_OBJECTION_INQUIRY(HttpStatus.BAD_REQUEST, "강의 내용 다름 이의 제기 문의가 아닙니다."),
    OBJECTION_ALREADY_RESOLVED(HttpStatus.CONFLICT, "이미 판단한 이의 제기입니다."),
    // 강의 모듈의 접수·판단·철회 거절 사유 (LectureObjectionRefusedException.Reason). 메시지는 강의 쪽 사유 메시지를 쓴다
    OBJECTION_NOT_BUYER(HttpStatus.NOT_FOUND, "구매자 본인의 주문 항목이 아닙니다."),
    OBJECTION_NOT_OBJECTIONABLE(HttpStatus.CONFLICT, "구매 취소되었거나 반환된 항목은 이의 제기할 수 없습니다."),
    OBJECTION_ALREADY_PAID_OUT(HttpStatus.CONFLICT, "이미 판매자에게 지급된 항목은 이의 제기할 수 없습니다."),
    OBJECTION_ALREADY_FILED(HttpStatus.CONFLICT, "이미 이의 제기한 항목입니다."),
    OBJECTION_PERIOD_EXPIRED(HttpStatus.CONFLICT, "이의 제기 기간이 지났습니다."),
    OBJECTION_NOT_FILED(HttpStatus.CONFLICT, "판단을 기다리는 이의 제기가 없습니다."),
    OBJECTION_INQUIRY_MISMATCH(HttpStatus.CONFLICT, "이의 제기를 접수한 문의가 아닙니다."),
    // 고객센터가 아직 코드를 두지 않은 새 사유. 메시지는 강의 쪽 사유 메시지로 응답한다
    OBJECTION_REFUSED(HttpStatus.CONFLICT, "이의 제기를 처리할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    /**
     * 강의 모듈의 거절 사유에 맞는 오류 코드. 강의가 사유를 추가해도 고객센터 컴파일이 깨지지 않게, 모르는 사유는
     * {@link #OBJECTION_REFUSED}로 응답한다. 프론트가 따로 분기해야 하는 사유면 여기에 코드를 추가한다.
     */
    public static CustomerSupportErrorCode of(LectureObjectionRefusedException.Reason reason) {
        return switch (reason) {
            case NOT_BUYER -> OBJECTION_NOT_BUYER;
            case NOT_OBJECTIONABLE -> OBJECTION_NOT_OBJECTIONABLE;
            case ALREADY_PAID_OUT -> OBJECTION_ALREADY_PAID_OUT;
            case ALREADY_FILED -> OBJECTION_ALREADY_FILED;
            case PERIOD_EXPIRED -> OBJECTION_PERIOD_EXPIRED;
            case NOT_FILED -> OBJECTION_NOT_FILED;
            case INQUIRY_MISMATCH -> OBJECTION_INQUIRY_MISMATCH;
            default -> OBJECTION_REFUSED;
        };
    }
}
