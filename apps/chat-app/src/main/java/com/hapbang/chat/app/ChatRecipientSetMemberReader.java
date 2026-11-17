package com.hapbang.chat.app;

import java.util.List;

/**
 * 불변 수신자 집합 항목 조회. 항목 복사(native SQL)와 이름으로 만드는 조회는 repository 메서드를 그대로 쓴다.
 */
public interface ChatRecipientSetMemberReader {

    /** 집합에 든 회원 ID(회원 ID 순) */
    List<Long> findUserIds(Long recipientSetId);
}
