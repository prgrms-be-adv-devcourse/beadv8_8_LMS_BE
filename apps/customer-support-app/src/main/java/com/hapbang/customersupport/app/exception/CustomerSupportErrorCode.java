package com.hapbang.customersupport.app.exception;

import org.springframework.http.HttpStatus;

import com.hapbang.customersupport.global.exception.BaseErrorCode;
import com.hapbang.shared.lecture.domain.LectureObjectionFailureCode;

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
    // 판단·철회 요청이 이미 처리 중이거나 접수가 아직 끝나지 않았다. 반대 요청이나 다른 내용의 요청도 이 코드다 (D60 (2))
    OBJECTION_IN_PROGRESS(HttpStatus.CONFLICT, "이의 제기를 처리하는 중입니다. 잠시 후 다시 확인해 주세요."),
    // 강의가 판단·철회를 거절해 운영 복구(#267)를 기다린다
    OBJECTION_RECOVERY_REQUIRED(HttpStatus.CONFLICT, "이의 제기 처리가 지연되어 확인 중입니다."),
    // 강의의 접수·판단·철회 거절 사유 (shared LectureObjectionFailureCode, #278). 메시지는 강의 쪽 사유 메시지와 같다
    OBJECTION_NOT_BUYER(HttpStatus.NOT_FOUND, "구매자 본인의 주문 항목이 아닙니다."),
    OBJECTION_NOT_OBJECTIONABLE(HttpStatus.CONFLICT, "구매 취소되었거나 반환된 항목은 이의 제기할 수 없습니다."),
    OBJECTION_ALREADY_PAID_OUT(HttpStatus.CONFLICT, "이미 판매자에게 지급된 항목은 이의 제기할 수 없습니다."),
    OBJECTION_ALREADY_FILED(HttpStatus.CONFLICT, "이미 이의 제기한 항목입니다."),
    OBJECTION_PERIOD_EXPIRED(HttpStatus.CONFLICT, "이의 제기 기간이 지났습니다. 구매일 다음 날부터 30일 이내에만 이의 제기할 수 있습니다."),
    OBJECTION_NOT_FILED(HttpStatus.CONFLICT, "판단을 기다리는 이의 제기가 없습니다."),
    OBJECTION_INQUIRY_MISMATCH(HttpStatus.CONFLICT, "이의 제기를 접수한 문의가 아닙니다."),
    // 강의가 같은 명령 ID의 다른 내용으로 거절했다(고객센터 결함). 사용자에게는 처리할 수 없다고만 알린다
    OBJECTION_REFUSED(HttpStatus.CONFLICT, "이의 제기를 처리할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    /** 강의 거절 사유에 맞는 오류 코드. 강의가 사유를 추가하면 shared 계약과 함께 여기도 컴파일 오류로 드러난다 */
    public static CustomerSupportErrorCode of(LectureObjectionFailureCode failureCode) {
        return switch (failureCode) {
            case OBJECTION_NOT_BUYER -> OBJECTION_NOT_BUYER;
            case OBJECTION_NOT_OBJECTIONABLE -> OBJECTION_NOT_OBJECTIONABLE;
            case OBJECTION_ALREADY_PAID_OUT -> OBJECTION_ALREADY_PAID_OUT;
            case OBJECTION_ALREADY_FILED -> OBJECTION_ALREADY_FILED;
            case OBJECTION_PERIOD_EXPIRED -> OBJECTION_PERIOD_EXPIRED;
            case OBJECTION_NOT_FILED -> OBJECTION_NOT_FILED;
            case OBJECTION_INQUIRY_MISMATCH -> OBJECTION_INQUIRY_MISMATCH;
            case OBJECTION_COMMAND_CONFLICT -> OBJECTION_REFUSED;
        };
    }
}
