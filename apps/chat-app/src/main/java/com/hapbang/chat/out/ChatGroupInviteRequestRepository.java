package com.hapbang.chat.out;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.domain.ChatGroupInviteRequest;

import jakarta.persistence.LockModeType;

public interface ChatGroupInviteRequestRepository extends JpaRepository<ChatGroupInviteRequest, Long> {

    /** 방·요청 ID의 초대 결과 기록 */
    Optional<ChatGroupInviteRequest> findByChatRoomIdAndRequestId(Long chatRoomId, UUID requestId);

    /**
     * 방·요청 ID의 초대 결과 기록을 잠그고 조회한다. 초대 트랜잭션이 보존 기간이 지난 행을 지우고 새 행을 만드는 사이 정리 배치가 그
     * 행을 먼저 지우지 못하게 한다(먼저 지우면 초대 트랜잭션의 삭제가 0행이 되어 실패한다). 정리 배치가 먼저 잠갔으면 지워질 때까지
     * 기다린 뒤 빈 값을 받고 새 행을 만든다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChatGroupInviteRequest r where r.chatRoomId = :chatRoomId and r.requestId = :requestId")
    Optional<ChatGroupInviteRequest> findForUpdate(@Param("chatRoomId") Long chatRoomId,
            @Param("requestId") UUID requestId);

    /**
     * 보존 기간이 지난 초대 결과 기록을 한 묶음 지운다. {@code recordedAt <= cutoff}(cutoff = 지금 − 보존 기간)는
     * {@link ChatGroupInviteRequest#isRetained}가 더는 보존하지 않는 행과 같다. 초대 트랜잭션이 잠근 행은 건너뛰고 다음 실행에서 지운다.
     * 후보는 {@code (recorded_at, id)} 인덱스 순서로 고르므로 지난 행만 읽고, 보존 중인 행은 훑지 않는다. 지운 행은 사라지므로 다음
     * 묶음도 인덱스 앞에서부터 다시 고르면 된다. 바깥 삭제는 고른 ID 배열로 기본 키를 찾는다(전체 테이블을 훑지 않는다).
     *
     * @return 지운 행 수
     */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            delete from chats.chat_group_invite_request
            where id = any(array(select id from chats.chat_group_invite_request
                                 where recorded_at <= :cutoff
                                 order by recorded_at, id
                                 limit :limit
                                 for update skip locked))
            """)
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
