package com.hapbang.chat.app;

/**
 * 로컬 연결 회원의 최신 채팅 자격을 다시 확인하고, 지금도 채팅할 수 없는 회원의 연결만 닫는다.
 * 지연된 옛 제어 명령으로 자격이 복구된 회원을 닫지 않는다. 상태 조회 실패는 닫지도 확인하지도 않은 것으로 남기고
 * 다음 주기 재검사가 회복한다.
 */
public interface ChatConnectionRecheckService {

    /** 한 회원을 재검사한다(RECHECK_USER). 로컬 연결이 없으면 조회하지 않는다 */
    void recheckUser(Long userId);

    /** 로컬 연결이 있는 모든 회원을 배치로 재검사한다. 주기 재검사와 Redis 구독 복구 직후에 쓴다 */
    void recheckAllConnected();
}
