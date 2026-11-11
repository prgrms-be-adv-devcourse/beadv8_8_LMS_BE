package com.hapbang.auxiliary.chatbot.out;

import java.util.Map;

import com.hapbang.shared.member.dto.MemberPageDto;

/**
 * 회원 원본 목록 (#717). 운영은 회원 내부 API({@link MemberApiSyncSource}), 테스트는 가짜로 바꿔 끼운다.
 * 실패는 shared {@code MemberApiCallException}(거절·일시 장애)이다.
 */
public interface MemberSyncSource {

    /** 이 ID보다 큰 회원(모든 역할·상태)을 ID 오름차순으로 size개까지. afterId가 null이면 처음부터 */
    MemberPageDto members(Long afterId, int size);

    /** 콘텐츠 분야 코드 → 한글 이름(사용이 꺼진 분야 포함). 인플루언서 프로필 임베딩 글에 쓴다 (#719) */
    Map<String, String> categoryNames();
}
