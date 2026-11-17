package com.hapbang.chat.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.chat.domain.ChatRoomMember;

/**
 * 채팅방 참여 행 저장소. JPQL 조회는 {@link ChatRoomMemberRepositoryCustom}(QueryDSL)에 있다.
 * <p>
 * 조회 구현 조각({@code ...CustomImpl})도 같은 app 조회 인터페이스의 빈이다. app이 그 인터페이스로 주입받을 때 이 repository
 * 프록시(예외 변환 포함)가 뽑히도록 {@code @Primary}로 둔다.
 */
@Primary
public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long>, ChatRoomMemberRepositoryCustom {

    List<ChatRoomMember> findByChatRoom_IdAndDeletedAtIsNull(Long chatRoomId);

    List<ChatRoomMember> findByChatRoom_IdInAndDeletedAtIsNull(Collection<Long> chatRoomIds);

    Optional<ChatRoomMember> findByChatRoom_IdAndUserIdAndDeletedAtIsNull(Long chatRoomId, Long userId);

    /** 방에서 주어진 회원들의 참여 행(나간 행 포함). 초대할 회원만 읽는다 */
    List<ChatRoomMember> findByChatRoom_IdAndUserIdInAndDeletedAtIsNull(Long chatRoomId, Collection<Long> userIds);

    /**
     * 회원 본인의 누적 수신 확인 순번을 올린다. 아래를 모두 만족하는 한 행만 바꾸고, 바꾼 행 수(0 또는 1)를 돌려준다.
     * <ul>
     *     <li>그 방의 그 회원 행이고 지금 참여 중이다(퇴장·삭제되지 않음).</li>
     *     <li>참여 세대가 요청한 세대와 같다. 확인 뒤 퇴장·재입장이 끼어들어도 옛 세대의 수신 확인이 새 세대에 반영되지 않는다.</li>
     *     <li>지금 값이 없거나(미확인) 요청한 순번보다 작다. 같은 값·낮은 값은 바꾸지 않아 동시에 와도 줄어들지 않는다.</li>
     * </ul>
     * 방 행을 잠그지 않고 참여 행 하나만 갱신한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChatRoomMember m set m.lastReceivedSequence = :sequence
            where m.chatRoom.id = :chatRoomId and m.userId = :userId
              and m.leftAt is null and m.deletedAt is null
              and m.participationVersion = :participationVersion
              and (m.lastReceivedSequence is null or m.lastReceivedSequence < :sequence)
            """)
    int advanceLastReceivedSequence(@Param("chatRoomId") Long chatRoomId, @Param("userId") Long userId,
            @Param("participationVersion") int participationVersion, @Param("sequence") long sequence);

    /**
     * 회원 본인의 읽음 순번을 현재 참여 세대 안에서만 올린다(읽음 처리 계약). 아래를 모두 만족하는 한 행만 바꾸고 바꾼 행 수(0 또는 1)를 돌려준다.
     * <ul>
     *     <li>그 방의 그 회원 행이고 지금 참여 중이다(퇴장·삭제되지 않음).</li>
     *     <li>참여 세대가 확인한 세대와 같다. 확인 뒤 퇴장·재입장이 끼어들면 반영하지 않는다.</li>
     *     <li>지금보다 큰 값이다. 같은 값·낮은 값은 바꾸지 않아 동시에 와도 줄어들지 않는다.</li>
     * </ul>
     * 방 행을 잠그지 않고 참여 행 하나만 갱신한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChatRoomMember m set m.lastReadSequence = :sequence
            where m.chatRoom.id = :chatRoomId and m.userId = :userId
              and m.leftAt is null and m.deletedAt is null
              and m.participationVersion = :participationVersion
              and m.lastReadSequence < :sequence
            """)
    int advanceLastReadSequenceInParticipation(@Param("chatRoomId") Long chatRoomId, @Param("userId") Long userId,
            @Param("participationVersion") int participationVersion, @Param("sequence") long sequence);
}
