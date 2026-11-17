package com.hapbang.chat.out;

import java.util.List;

import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.chat.domain.ChatRecipientSetMember;

/**
 * 불변 수신자 집합 항목 저장소. JPQL 조회는 {@link ChatRecipientSetMemberRepositoryCustom}(QueryDSL)에 있다.
 * <p>
 * 조회 구현 조각({@code ...CustomImpl})도 같은 app 조회 인터페이스의 빈이다. app이 그 인터페이스로 주입받을 때 이 repository
 * 프록시(예외 변환 포함)가 뽑히도록 {@code @Primary}로 둔다.
 */
@Primary
public interface ChatRecipientSetMemberRepository
        extends JpaRepository<ChatRecipientSetMember, ChatRecipientSetMember.Key>,
        ChatRecipientSetMemberRepositoryCustom {

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

    /** 집합의 항목 전체(회원 ID 순). 커밋된 집합을 전달 payload로 펼칠 때 쓴다 */
    List<ChatRecipientSetMember> findByRecipientSetIdOrderByUserIdAsc(Long recipientSetId);
}
