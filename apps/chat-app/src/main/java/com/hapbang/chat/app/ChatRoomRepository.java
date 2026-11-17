package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.chat.domain.ChatRoom;

import jakarta.persistence.LockModeType;

/**
 * 채팅방 저장소. 잠그지 않는 JPQL 조회는 {@link ChatRoomRepositoryCustom}(QueryDSL)에 있고, 잠그는 조회({@code @Lock})는 여기 있다.
 */
public interface ChatRoomRepository extends Repository<ChatRoom, Long>, ChatRoomRepositoryCustom {

    // 기본 메서드: 이름과 시그니처가 같아 Spring Data 기본 구현(SimpleJpaRepository)이 처리한다
    <S extends ChatRoom> S saveAndFlush(S entity);
    Optional<ChatRoom> findById(Long id);

    // 테스트 검증에서만 쓰는 기본 메서드
    List<ChatRoom> findAll();
    long count();

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
