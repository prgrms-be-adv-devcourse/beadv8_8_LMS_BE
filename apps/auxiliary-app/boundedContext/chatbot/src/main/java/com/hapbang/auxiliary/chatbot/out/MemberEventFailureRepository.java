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

    /** 전체 재처리 대상(오래된 순) */
    @Query("SELECT f.id FROM MemberEventFailure f WHERE f.status = :status ORDER BY f.id")
    List<Long> findIdsByStatus(@Param("status") MemberEventFailureStatus status, Pageable pageable);

    /** 행 락을 잡는다. 다른 트랜잭션이 잡고 있으면 기다리지 않고 비어 있다(여러 관리자가 동시에 누름) */
    @Query(value = "SELECT * FROM chatbot.member_event_failure WHERE id = :id FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<MemberEventFailure> lockById(@Param("id") long id);

    /** 락 없이 상태만 본다 */
    @Query("SELECT f.status FROM MemberEventFailure f WHERE f.id = :id")
    Optional<MemberEventFailureStatus> findStatusById(@Param("id") long id);

    /** 이 회원의 원문이 남은 기록(마스킹할 때 개인 정보를 비운다) */
    List<MemberEventFailure> findByUserIdAndStatusAndPayloadIsNotNull(Long userId, MemberEventFailureStatus status);
}
