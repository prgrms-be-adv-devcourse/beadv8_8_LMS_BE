package com.hapbang.member.out;

import com.hapbang.shared.member.dto.WithdrawalBlocksDto;

/**
 * 탈퇴가 막힌 이유를 알려 주는 앱 (#593, docs/api/internal-withdrawals.md 3장). 출연·강의가 읽기만 하는 확인이라 되돌릴 것이 없다.
 * 구현은 각 앱이 shared Client 메서드를 만든 뒤 그 Client를 부르는 연결 코드로 더한다
 */
public interface WithdrawalBlockSource {

    /** 로그에 남길 앱 이름 (예: appearance) */
    String name();

    /**
     * 그 회원의 탈퇴가 막힌 이유. 없으면 빈 목록
     *
     * @throws WithdrawalCallException 답을 받지 못했을 때
     */
    WithdrawalBlocksDto blocks(long userId);
}
