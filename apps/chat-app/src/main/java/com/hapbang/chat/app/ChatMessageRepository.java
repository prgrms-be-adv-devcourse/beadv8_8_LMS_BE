package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.chat.app.ChatReadCountRows.MessageUnreadRow;
import com.hapbang.chat.app.ChatReadCountRows.RecentUnreadRow;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.NotificationKind;

/**
 * 채팅 메시지 저장소. JPQL 조회는 {@link ChatMessageRepositoryCustom}(QueryDSL)에 있다.
 */
public interface ChatMessageRepository extends Repository<ChatMessage, Long>, ChatMessageRepositoryCustom {

    // 기본 메서드: 이름과 시그니처가 같아 Spring Data 기본 구현(SimpleJpaRepository)이 처리한다
    <S extends ChatMessage> S save(S entity);

    // 테스트 검증에서만 쓰는 기본 메서드
    Optional<ChatMessage> findById(Long id);
    List<ChatMessage> findAll();
    long count();

    /**
     * 주어진 일반 메시지({@code TEXT})마다 안 읽은 현재 참여자 수를 한 문장으로 센다. 메시지마다 참여자를 따로 읽지 않는다.
     * <p>
     * 메시지 순번 s의 값은 {@code 현재 참여 중 AND userId != 보낸 사람 AND visibleFromSequence <= s AND lastReadSequence < s}인
     * 참여 행 수다(설계 3.5). 늦게 초대·재입장한 회원은 가시 범위가 s 뒤라 들지 않고, 나간·강퇴된 회원은 빠진다. 한 회원은 기기 수와
     * 관계없이 한 행이다. {@code read_count_version}은 이 문장의 시작 시각(epoch 마이크로초)이다.
     * 일반 메시지가 아닌 ID는 결과에서 빠진다. 빈 목록으로 부르지 않는다.
     */
    @Query(nativeQuery = true, value = """
            select m.id as message_id, count(cm.id) as unread_member_count,
                   cast(extract(epoch from statement_timestamp()) * 1000000 as bigint) as read_count_version
            from chats.chat_message m
                     left join chats.chat_room_member cm
                               on cm.chat_room_id = m.chat_room_id
                                   and cm.left_at is null and cm.deleted_at is null
                                   and cm.user_id <> m.sender_user_id
                                   and cm.visible_from_sequence <= m.room_sequence
                                   and cm.last_read_sequence < m.room_sequence
            where m.id in (:messageIds) and m.message_type = 'TEXT'
            group by m.id
            """)
    List<MessageUnreadRow> countUnreadMembers(@Param("messageIds") Collection<Long> messageIds);

    /**
     * 방의 마지막 {@code window}개 순번([fromSequence, toSequence]) 안의 일반 메시지마다 안 읽은 현재 참여자 수를 한 문장으로 센다.
     * 조건은 {@link #countUnreadMembers}와 같다. 방이 있으면 구간에 일반 메시지가 없어도 구간 행 하나({@code room_sequence} null)가
     * 나온다. 같은 스냅샷에서 {@code to_sequence}는 방 마지막 순번, {@code recipient_set_version}은 방의 현재 수신자 집합 버전(없으면
     * 0)이다. 받는 구독은 이 버전 이상을 반영한 참여 상태로 고른다.
     * <p>
     * 참여자가 볼 수 없는 방(없음·삭제·종료된 1:1 방·조회 만료된 단체방)이면 빈 목록이다. 조건은 {@code ChatRoom.isViewable(now)}와
     * 같다: 단체방은 사용 중·일시 중지이거나, 읽기 전용이고 조회 만료 시각 전(그 시각 포함 만료)이다.
     */
    @Query(nativeQuery = true, value = """
            with r as (select id, last_sequence, current_recipient_set_id,
                              greatest(last_sequence - :window + 1, 1) as from_sequence
                       from chats.chat_room
                       where id = :chatRoomId and deleted_at is null
                         and ((room_type <> 'GROUP' and status <> 'ENDED')
                              or (room_type = 'GROUP'
                                  and (status in ('ACTIVE', 'SUSPENDED')
                                       or (status = 'READ_ONLY'
                                           and (history_expires_at is null or history_expires_at > :now))))))
            select r.from_sequence as from_sequence, r.last_sequence as to_sequence,
                   coalesce(s.set_version, 0) as recipient_set_version,
                   m.room_sequence as room_sequence, count(cm.id) as unread_member_count,
                   cast(extract(epoch from statement_timestamp()) * 1000000 as bigint) as read_count_version
            from r
                     left join chats.chat_recipient_set s on s.id = r.current_recipient_set_id
                     left join chats.chat_message m
                               on m.chat_room_id = r.id and m.room_sequence >= r.from_sequence
                                   and m.message_type = 'TEXT' and m.deleted_at is null
                     left join chats.chat_room_member cm
                               on cm.chat_room_id = r.id
                                   and cm.left_at is null and cm.deleted_at is null
                                   and cm.user_id <> m.sender_user_id
                                   and cm.visible_from_sequence <= m.room_sequence
                                   and cm.last_read_sequence < m.room_sequence
            group by r.from_sequence, r.last_sequence, s.set_version, m.room_sequence
            """)
    List<RecentUnreadRow> countRecentUnreadMembers(@Param("chatRoomId") long chatRoomId, @Param("window") int window,
            @Param("now") LocalDateTime now);

    /** 안 읽은 사람 수 버전과 같은 단위의 지금 DB 시각(epoch 마이크로초). 센 일반 메시지가 없는 페이지에 쓴다 */
    @Query(nativeQuery = true, value = "select cast(extract(epoch from statement_timestamp()) * 1000000 as bigint)")
    long currentReadCountVersion();

    /**
     * 회원이 이 방에서 보낸 메시지의 전송 ID를 비운다. 재입장할 때 불러, 재전송 방지를 같은 참여 안으로 한정한다.
     * 재입장 처리 중인 엔티티가 영속성 컨텍스트에서 떨어지지 않도록 비우지(clear) 않는다.
     *
     * @return 비운 메시지 수
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ChatMessage m set m.clientMessageId = null
            where m.chatRoom.id = :chatRoomId and m.senderUserId = :senderUserId and m.clientMessageId is not null
            """)
    int clearClientMessageIds(@Param("chatRoomId") Long chatRoomId, @Param("senderUserId") Long senderUserId);

    /** 같은 전송 ID로 이미 저장한 메시지 (재전송 확인) */
    Optional<ChatMessage> findByChatRoom_IdAndSenderUserIdAndClientMessageId(Long chatRoomId, Long senderUserId,
            String clientMessageId);

    /** 같은 출연 이벤트로 이미 알림을 남겼는지 */
    boolean existsByNotificationKindAndReferenceId(NotificationKind notificationKind, Long referenceId);

    /** 방의 모든 메시지 (Soft Delete 포함, 순번 순서). 테스트 검증에서만 쓴다 */
    List<ChatMessage> findByChatRoom_IdOrderByRoomSequenceAsc(Long chatRoomId);

    /**
     * 관리자 기록 조회 첫 페이지. 참여자별 조회 범위와 Soft Delete 여부와 관계없이 (보낸 시각, ID) 순서로 조회한다.
     */
    List<ChatMessage> findByChatRoom_IdOrderBySentAtAscIdAsc(Long chatRoomId, Limit limit);
}
