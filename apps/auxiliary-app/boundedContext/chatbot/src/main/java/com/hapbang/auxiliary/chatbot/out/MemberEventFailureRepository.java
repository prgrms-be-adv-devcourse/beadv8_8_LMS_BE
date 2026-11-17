package com.hapbang.auxiliary.chatbot.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.auxiliary.chatbot.domain.MemberEventFailure;
import com.hapbang.auxiliary.chatbot.domain.MemberEventFailureStatus;

/** 회원 이벤트 실패 기록 (#976) */
public interface MemberEventFailureRepository extends JpaRepository<MemberEventFailure, Long> {

    /** 같은 메시지 위치를 이미 기록했는지 */
    boolean existsBySourceTopicAndSourcePartitionAndSourceOffset(String sourceTopic, int sourcePartition, long sourceOffset);

    /** 관리자 목록(최신순) */
    Page<MemberEventFailure> findByStatusOrderByIdDesc(MemberEventFailureStatus status, Pageable pageable);

    /** 전체 재처리 대상(다시 처리할 수 있는 것만, 오래된 순) */
    @Query("SELECT f.id FROM MemberEventFailure f WHERE f.status = :status AND f.replayable = true ORDER BY f.id")
    List<Long> findReplayableIds(@Param("status") MemberEventFailureStatus status, Pageable pageable);

    /** 행 락을 잡는다. 부르는 쪽이 그 기록의 회원 락을 먼저 잡는다(마스킹과 같은 순서) */
    @Query(value = "SELECT * FROM chatbot.member_event_failure WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<MemberEventFailure> lockById(@Param("id") long id);

    /** 락 없이 기록의 회원 ID를 본다. 기록이 없으면 빈 목록, 회원 ID가 없는 기록이면 null 하나 */
    @Query("SELECT f.userId FROM MemberEventFailure f WHERE f.id = :id")
    List<Long> findUserIdById(@Param("id") long id);

    /** 이 회원의 원문이 남은 기록(마스킹할 때 개인 정보를 비운다) */
    List<MemberEventFailure> findByUserIdAndStatusAndPayloadIsNotNull(Long userId, MemberEventFailureStatus status);
}
