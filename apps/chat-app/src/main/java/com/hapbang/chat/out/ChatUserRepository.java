package com.hapbang.chat.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.chat.domain.ChatUser;

import jakarta.persistence.LockModeType;

public interface ChatUserRepository extends JpaRepository<ChatUser, Long> {

    /**
     * 회원 복제본을 잠그고 읽는다. 단체방 생성이 호스트 자격을 확인할 때 쓴다. 회원 변경 반영도 같은 행을 갱신하며 잠그므로, 생성과
     * 호스트 자격 상실 반영이 서로 엇갈리지 않는다(먼저 잠근 쪽이 커밋한 뒤 다른 쪽이 그 결과를 본다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from ChatUser u where u.id = :id")
    Optional<ChatUser> findByIdForUpdate(@Param("id") Long id);
}
