package com.hapbang.auxiliary.notification.in.event.kafka;

import java.util.function.Supplier;

/**
 * 리스너의 검증 변환(ND-181). 이벤트를 알림 초안·예약으로 바꾸다 값 검증에 실패하면({@link IllegalArgumentException}) 다시 처리해도 같으므로
 * {@link RejectedEventException}으로 바꿔 재시도 없이 DLT로 보낸다. 저장 단계의 오류(받는 사람 복제본 없음, DB 장애)는 바꾸지 않아
 * 블로킹 재시도 뒤 DLT로 간다(ND-010·ND-035).
 */
public final class RejectedEvents {

    private RejectedEvents() {
    }

    /**
     * @param message 검증에 실패했을 때 예외 메시지(원인은 검증 예외)
     * @param convert 이벤트를 바꾸는 일
     * @return 바꾼 값
     * @throws RejectedEventException 값 검증 실패
     */
    public static <T> T orReject(String message, Supplier<T> convert) {
        try {
            return convert.get();
        } catch (IllegalArgumentException e) {
            throw new RejectedEventException(message, e);
        }
    }
}
