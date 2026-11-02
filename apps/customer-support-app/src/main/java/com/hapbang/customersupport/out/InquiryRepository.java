package com.hapbang.customersupport.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryStatus;

import jakarta.persistence.LockModeType;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

    Page<Inquiry> findByUserIdAndDeletedAtIsNull(Long userId, Pageable pageable);

    /**
     * 내 문의 중 제목이나 내용이 검색 패턴에 맞는 것. 패턴은 소문자이고 {@code %}, {@code _}, {@code \}를 {@code \}로 이스케이프한다.
     */
    @Query("""
            select i from Inquiry i
            where i.userId = :userId and i.deletedAt is null
              and (lower(i.title) like :pattern escape '\\' or lower(i.content) like :pattern escape '\\')
            """)
    Page<Inquiry> searchByUserId(@Param("userId") Long userId, @Param("pattern") String pattern, Pageable pageable);

    Optional<Inquiry> findByIdAndDeletedAtIsNull(Long id);

    /**
     * 답변까지 함께 불러온다. 답변을 바꾼 뒤 커밋하고 트랜잭션 밖(Facade)에서 응답을 만들 때 지연 로딩이 일어나지 않게 한다.
     */
    @EntityGraph(attributePaths = "replies")
    Optional<Inquiry> findWithRepliesByIdAndDeletedAtIsNull(Long id);

    /** 관리자 문의 목록 (전체) */
    Page<Inquiry> findByDeletedAtIsNull(Pageable pageable);

    /** 관리자 문의 목록 (상태별) */
    Page<Inquiry> findByStatusAndDeletedAtIsNull(InquiryStatus status, Pageable pageable);

    /**
     * 문의 행을 잠가 읽는다(삭제된 문의 포함). 문의를 바꾸는 모든 쓰기(답변, 삭제, 이의 제기 판단·철회 요청, 강의 결과 반영)는
     * 이 잠금 뒤에 바꾼다. 강의 결과 반영과 관리자 답변이 겹쳐도 한쪽 변경을 덮어쓰지 않게 하고, 판단·철회 선점이 접수됨(FILED)일 때
     * 하나만 되게 한다 (#279, D71). 답변은 잠근 뒤 같은 트랜잭션에서 읽는다(바깥 조인은 잠글 수 없다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inquiry i where i.id = :id")
    Optional<Inquiry> findForUpdate(@Param("id") Long id);

    /** 이 시각 전에 마지막으로 바뀐, 강의 접수 처리 중인 이의 제기 문의 (접수 재시도 배치). 오래된 문의부터 */
    @Query("""
            select i.id from Inquiry i
            where i.objectionLinkStatus = com.hapbang.customersupport.domain.ObjectionLinkStatus.FILING
              and i.updatedAt < :before
            order by i.id asc
            """)
    List<Long> findFilingIdsUpdatedBefore(@Param("before") LocalDateTime before, Pageable pageable);
}
