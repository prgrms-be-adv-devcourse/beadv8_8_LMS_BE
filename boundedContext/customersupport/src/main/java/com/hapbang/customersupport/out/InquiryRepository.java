package com.hapbang.customersupport.out;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryStatus;

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

    /** 관리자 문의 목록 (전체) */
    Page<Inquiry> findByDeletedAtIsNull(Pageable pageable);

    /** 관리자 문의 목록 (상태별) */
    Page<Inquiry> findByStatusAndDeletedAtIsNull(InquiryStatus status, Pageable pageable);
}
