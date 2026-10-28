package com.hapbang.billing.out;

// Mock 은행의 가상 송금 결과 시나리오 (#288). 요청 값(계좌번호 등)으로 결과를 정하는 숨은 규칙은 없다.
// 테스트·로컬에서 MockPayoutScenarios(앱 안의 빈, HTTP로 열지 않음)나 기본값 설정으로만 고른다.
public enum MockPayoutScenario {
    /** 가상 송금 성공(200 SUCCEEDED) */
    SUCCESS,
    /** 가상 송금 확정 실패(200 FAILED). 은행이 거절했다는 결과이며 장애가 아니다 */
    FAILED,
    /** 응답 지연(delay) 뒤 성공. 지연이 응답 시간 제한 안이면 클라이언트는 성공을 받는다 */
    DELAY,
    /** 성공 처리·기록 후 응답만 시간 제한보다 늦게 보낸다(응답 유실). 클라이언트는 UNKNOWN, 결과 조회로 성공을 확인한다 */
    TIMEOUT,
    /** 기록 전에 HTTP 500으로 끝난다(처리하지 않음). 클라이언트는 UNKNOWN, 결과 조회는 NOT_FOUND */
    SERVER_ERROR
}
