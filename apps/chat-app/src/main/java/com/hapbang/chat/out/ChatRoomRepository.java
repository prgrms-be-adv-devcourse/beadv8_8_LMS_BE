package com.hapbang.chat.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.chat.domain.ChatRoom;

import jakarta.persistence.LockModeType;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    /**
     * 그 회원이 호스트인 현재 단체방(사용 중·일시 중지)을 잠그고 ID 순으로 조회한다. 호스트 자격 상실 반영이 쓴다.
     * 여러 방을 같은 순서로 잠가 다른 잠금 순서와 엇갈리지 않게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from ChatRoom r
            where r.roomType = com.hapbang.chat.domain.ChatRoomType.GROUP
              and r.ownerUserId = :ownerUserId
              and r.status in (com.hapbang.chat.domain.ChatRoomStatus.ACTIVE,
                               com.hapbang.chat.domain.ChatRoomStatus.SUSPENDED)
              and r.deletedAt is null
            order by r.id
            """)
    List<ChatRoom> findCurrentGroupsByOwnerForUpdate(@Param("ownerUserId") Long ownerUserId);

    /**
     * 방을 잠그고 조회한다. 메시지 순번 발급, 입장·퇴장·종료는 이 잠금 안에서 처리한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChatRoom r where r.id = :id and r.deletedAt is null")
    Optional<ChatRoom> findByIdForUpdate(@Param("id") Long id);

    /**
     * 두 회원의 종료되지 않은 1:1 방을 잠그고 조회한다. 종료된 방은 directKey가 비어 있어 조회되지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChatRoom r where r.directKey = :directKey and r.deletedAt is null")
    Optional<ChatRoom> findActiveDirectForUpdate(@Param("directKey") String directKey);

    /**
     * 모집글의 현재 단체방(사용 중·일시 중지). 부분 유일 인덱스로 하나뿐이다. 읽기 전용으로 남은 이전 방은 나오지 않는다.
     */
    @Query("""
            select r from ChatRoom r
            where r.recruitId = :recruitId and r.roomType = com.hapbang.chat.domain.ChatRoomType.GROUP
              and r.status in (com.hapbang.chat.domain.ChatRoomStatus.ACTIVE,
                               com.hapbang.chat.domain.ChatRoomStatus.SUSPENDED)
              and r.deletedAt is null
            """)
    Optional<ChatRoom> findCurrentGroup(@Param("recruitId") Long recruitId);

    /**
     * 조회 만료 시각에 도달한(같은 시각 포함) 읽기 전용 단체방 ID 한 묶음. 방 ID 순서의 키셋이며 잠그지 않는다. 상태는 방마다 잠근
     * 트랜잭션에서 다시 확인하고 바꾼다. 부분 인덱스 {@code idx_chat_room_group_history_expiry}를 쓴다.
     */
    @Query("""
            select r.id from ChatRoom r
            where r.roomType = com.hapbang.chat.domain.ChatRoomType.GROUP
              and r.status = com.hapbang.chat.domain.ChatRoomStatus.READ_ONLY
              and r.historyExpiresAt <= :now and r.id > :afterId and r.deletedAt is null
            order by r.id asc
            """)
    List<Long> findHistoryExpiryDueIds(@Param("now") LocalDateTime now, @Param("afterId") long afterId, Limit limit);

    /**
     * 단체방 제목을 모집글 제목으로 맞춘다. 방을 잠그지 않고 제목 컬럼만 바꾼다(엔티티 전체를 저장하면 그 사이 잠금 아래 바뀐 순번·인원을
     * 옛 값으로 되돌린다). 같은 제목이면 바꾸지 않는다.
     *
     * @return 바꾼 행 수(0 또는 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChatRoom r set r.title = :title, r.updatedAt = :now
            where r.id = :id and r.roomType = com.hapbang.chat.domain.ChatRoomType.GROUP and r.title <> :title
            """)
    int updateGroupTitle(@Param("id") Long id, @Param("title") String title, @Param("now") LocalDateTime now);
}
