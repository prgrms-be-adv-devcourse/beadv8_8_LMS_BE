package com.hapbang.shared.lecture.exception;

import java.util.Objects;

import com.hapbang.shared.lecture.port.LectureObjectionPort;

import lombok.Getter;

/**
 * 강의 내용 다름 이의 제기를 접수·판단할 수 없을 때 {@link LectureObjectionPort}가 던지는 예외.
 * <p>
 * shared는 global에 의존하지 않으므로 {@code BusinessException}이 아니다. 공통 핸들러는 이 예외를 500으로 응답하므로,
 * 호출한 쪽(고객센터)이 잡아서 사유 코드에 맞는 자기 업무 예외로 바꿔 던진다. 예외가 난 트랜잭션은 이미 롤백 전용이다.
 */
@Getter
public class LectureObjectionRefusedException extends RuntimeException {

    private final Reason reason;

    public LectureObjectionRefusedException(Reason reason) {
        super(Objects.requireNonNull(reason, "사유는 필수입니다.").getMessage());
        this.reason = reason;
    }

    @Getter
    public enum Reason {
        /** 접수: 구매자 본인 항목이 아니다 (없는 항목 포함) */
        NOT_BUYER("구매자 본인의 주문 항목이 아닙니다."),
        /** 접수: 구매 취소되었거나 이미 반환된 항목이다 */
        NOT_OBJECTIONABLE("구매 취소되었거나 반환된 항목은 이의 제기할 수 없습니다."),
        /** 접수: 이미 판매자에게 지급된 항목이다 */
        ALREADY_PAID_OUT("이미 판매자에게 지급된 항목은 이의 제기할 수 없습니다."),
        /** 접수: 이의 제기가 이미 접수되었거나 기각된 항목이다 */
        ALREADY_FILED("이미 이의 제기한 항목입니다."),
        /** 접수: 지급 예정 시각(구매일 다음 날부터 30일 경과)이 지났다 */
        PERIOD_EXPIRED("이의 제기 기간이 지났습니다. 구매일 다음 날부터 30일 이내에만 이의 제기할 수 있습니다."),
        /** 판단: 접수되어 판단을 기다리는 항목이 아니다 (없는 항목, 이미 판단한 항목 포함) */
        NOT_FILED("판단을 기다리는 이의 제기가 없습니다."),
        /** 판단: 접수할 때의 문의와 다른 문의로 판단하려 했다 */
        INQUIRY_MISMATCH("이의 제기를 접수한 문의가 아닙니다.");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }
}
