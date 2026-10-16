package com.hapbang.shared.lecture.domain;

/**
 * 강의 판매 수익의 지급 진행 상태. 강의 내부 수익 조회 API(#300)의 계약이다.
 * <p>
 * 수익(earning)은 판매자가 받을 지급 한 건(주문 항목 하나)이다. 이미 지갑에 들어온 수익 HP와는 다르다.
 * 출연의 수익 상태와 상수 이름을 맞춘다. 상수 이름은 HTTP 응답 문자열이므로 바꾸지 않는다.
 * 앞의 다섯 상태는 아직 지급하지 않은 수익이고, {@link #COMPLETED}·{@link #CANCELED}는 끝난 수익이다.
 */
public enum LectureEarningStatus {
    /** 구매 확정 대기. 구매자가 구매 취소할 수 있다 */
    CONFIRMATION_PENDING,
    /** 지급 예정. 구매 확정되었고 지급 예정 시각 전이다 */
    SCHEDULED,
    /** 지급 중단(이의 제기). 판단 결과에 따라 지급하거나 구매자에게 반환한다 */
    ON_HOLD,
    /** 처리 중. 지급 예정 시각이 지나 다음 지급 배치에서 지급한다 */
    PROCESSING,
    /** 지급 실패. 다음 지급 배치가 다시 시도하거나 관리자 조치를 기다린다 */
    FAILED,
    /** 지급 완료 */
    COMPLETED,
    /** 구매 취소 또는 이의 제기 인정으로 구매자에게 반환되어 지급하지 않는다 */
    CANCELED;

    /** 아직 지급하지 않은 수익인지. 요약은 이 상태만 합한다 */
    public boolean isUnpaid() {
        return this != COMPLETED && this != CANCELED;
    }
}
