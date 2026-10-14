package com.hapbang.lecture.domain;

import org.springframework.http.HttpStatus;

import com.hapbang.global.exception.BaseErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LectureErrorCode implements BaseErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    INVALID_PRICE(HttpStatus.BAD_REQUEST, "강의 가격은 1,000 HP 이상, 1,000 HP 단위여야 합니다."),
    CONTENT_REQUIRED(HttpStatus.BAD_REQUEST, "강의 콘텐츠는 1개 이상이어야 합니다."),
    NOT_LECTURE_SELLER(HttpStatus.FORBIDDEN, "강의 판매자만 처리할 수 있습니다."),
    LECTURE_NOT_FOUND(HttpStatus.NOT_FOUND, "강의를 찾을 수 없습니다."),
    NOT_PENDING_INSPECTION(HttpStatus.CONFLICT, "검수 대기 중인 강의가 아닙니다."),
    NOT_REJECTED(HttpStatus.CONFLICT, "반려된 강의만 재검수를 신청할 수 있습니다."),
    CONTENT_NOT_EDITABLE(HttpStatus.CONFLICT, "반려된 강의만 영상·자료를 수정할 수 있습니다. 판매 중인 강의는 삭제 후 다시 등록해 주세요."),
    REJECTION_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "반려 사유를 입력해 주세요."),
    LECTURE_USER_NOT_FOUND(HttpStatus.NOT_FOUND, "강의 회원 정보를 찾을 수 없습니다."),
    OWN_LECTURE_NOT_PURCHASABLE(HttpStatus.FORBIDDEN, "본인 강의는 담거나 구매할 수 없습니다."),
    ALREADY_IN_CART(HttpStatus.CONFLICT, "이미 장바구니에 담은 강의입니다."),
    ALREADY_OWNED_LECTURE(HttpStatus.CONFLICT, "이미 구매한 강의입니다."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    ORDER_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "주문 항목을 찾을 수 없습니다."),
    ORDER_ITEM_NOT_PURCHASED(HttpStatus.CONFLICT, "이미 구매 확정·취소된 항목입니다."),
    CANCEL_PERIOD_EXPIRED(HttpStatus.CONFLICT, "구매 취소 기간이 지났습니다. 구매일 다음 날부터 7일 이내에만 취소할 수 있습니다."),
    OBJECTION_FILED(HttpStatus.CONFLICT, "이의 제기가 접수된 항목은 구매 취소할 수 없습니다."),
    FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "파일을 찾을 수 없습니다."),
    NOT_FILE_UPLOADER(HttpStatus.FORBIDDEN, "파일을 올린 회원만 처리할 수 있습니다."),
    UNSUPPORTED_FILE_CONTENT_TYPE(HttpStatus.BAD_REQUEST, "이 용도로 올릴 수 없는 파일 형식입니다."),
    FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "파일이 이 용도의 최대 크기를 넘습니다."),
    FILE_SIZE_MISMATCH(HttpStatus.BAD_REQUEST, "올린 파일의 크기가 등록한 크기와 다릅니다."),
    FILE_CONTENT_TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "올린 파일의 형식(Content-Type)이 등록한 형식과 다릅니다."),
    FILE_LENGTH_REQUIRED(HttpStatus.LENGTH_REQUIRED, "파일 크기(Content-Length)를 알 수 없습니다. Content-Length 헤더와 함께 보내 주세요."),
    FILE_ALREADY_UPLOADED(HttpStatus.CONFLICT, "이미 올린 파일입니다. 다른 파일을 올리려면 파일 정보를 새로 등록해 주세요."),
    INVALID_LECTURE_FILE(HttpStatus.BAD_REQUEST, "강의에 연결할 수 없는 파일입니다."),
    LECTURE_CONTENT_NOT_FOUND(HttpStatus.NOT_FOUND, "강의 콘텐츠를 찾을 수 없습니다."),
    CONTENT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "강의 콘텐츠를 볼 수 있는 회원이 아닙니다."),
    PLAY_CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "재생 동의 후 볼 수 있습니다. 재생하면 구매가 확정되어 단순 변심으로 구매 취소할 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
