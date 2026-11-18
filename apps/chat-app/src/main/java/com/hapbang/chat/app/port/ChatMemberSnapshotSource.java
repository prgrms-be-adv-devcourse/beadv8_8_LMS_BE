package com.hapbang.chat.app.port;

import com.hapbang.shared.member.dto.MemberPageDto;

/**
 * 백필에 쓸 회원 원본 스냅샷을 회원 ID 오름차순 키셋 페이지로 읽는다(설계 문서 5.4, {@code docs/api/internal-members.md}).
 * 호출 중에 chat DB 트랜잭션을 열어 두지 않는다.
 */
public interface ChatMemberSnapshotSource {

    /**
     * afterId보다 큰 회원을 최대 size명 읽는다.
     *
     * @throws ChatMemberSourceException 회원 원본이 거절했거나 응답을 받지 못했을 때
     */
    MemberPageDto readPage(long afterId, int size);
}
