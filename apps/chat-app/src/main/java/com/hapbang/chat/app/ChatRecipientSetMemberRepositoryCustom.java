package com.hapbang.chat.app;

import java.util.List;

/**
 * {@link ChatRecipientSetMemberRepository}의 QueryDSL 조회. 구현은 out의
 * {@code ChatRecipientSetMemberRepositoryCustomImpl}이다. 항목 복사(native SQL)와 이름으로 만드는 조회는
 * {@link ChatRecipientSetMemberRepository}에 둔다.
 */
public interface ChatRecipientSetMemberRepositoryCustom {

    /** 집합에 든 회원 ID(회원 ID 순) */
    List<Long> findUserIds(Long recipientSetId);
}
