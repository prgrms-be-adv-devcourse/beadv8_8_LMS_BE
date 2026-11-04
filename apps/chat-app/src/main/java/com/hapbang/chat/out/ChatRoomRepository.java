package com.hapbang.chat.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.chat.domain.ChatRoom;

import jakarta.persistence.LockModeType;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

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
}
