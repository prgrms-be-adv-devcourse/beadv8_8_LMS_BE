package com.hapbang.chat.app;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.chat.domain.ChatRecipientSetMember;

/**
 * 불변 수신자 집합 항목 저장소. 항목은 방을 잠근 트랜잭션에서 native 문장으로 한 번에 복사한다.
 */
public interface ChatRecipientSetMemberRepository
        extends Repository<ChatRecipientSetMember, ChatRecipientSetMember.Key> {

    /**
     * 방의 지금 활성 참여 행을 새 수신자 집합의 항목으로 복사한다. 참여자 엔티티를 앱으로 읽지 않고 DB 안에서 한 번에 넣는다.
     * 같은 트랜잭션의 참여 행 변경이 먼저 반영되도록 실행 전에 flush한다. 방을 잠근 트랜잭션에서만 부른다.
     *
     * @return 넣은 항목 수
     */
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            insert into chats.chat_recipient_set_member (recipient_set_id, user_id, participation_version)
            select :recipientSetId, m.user_id, m.participation_version
            from chats.chat_room_member m
            where m.chat_room_id = :chatRoomId and m.left_at is null and m.deleted_at is null
            """)
    int copyActiveMembers(@Param("recipientSetId") Long recipientSetId, @Param("chatRoomId") Long chatRoomId);
}
