package com.hapbang.auxiliary.notification.loadtest;

/** B0 레코드 분류. 알림 레코드 하나는 한 분류에만 속한다(오류 유형끼리 겹치지 않음) */
public enum B0Category {
    /** 회원 가입(복제본) */
    MEMBER,
    /** 정상 알림 이벤트 */
    NORMAL,
    /** 정상 이벤트를 같은 key·본문으로 한 번 더 보냄 */
    RESEND,
    /** #818 이전 형식(ND-086). 알림 없이 커밋 */
    LEGACY,
    /** 받는 사람 복제본이 없음. 재시도 뒤 DLT(ND-035) */
    UNKNOWN_RECEIVER,
    /** 깨진 본문. 재시도 없이 DLT */
    BROKEN;

    /** 실패 단계(ND-166)에 넣는 분류. 재시도 대기가 정상 단계 성능 지표를 덮지 않게 정상 단계가 끝난 뒤 넣는다 */
    public boolean failurePhase() {
        return this == UNKNOWN_RECEIVER || this == BROKEN;
    }
}
