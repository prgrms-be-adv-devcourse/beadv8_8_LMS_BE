package com.hapbang.coupon.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.coupon.domain.CouponDltStatus;

/**
 * DLT 격리 기록 저장소. 같은 메시지 위치는 한 번({@code uk_coupon_dlt_log_source}), 같은 발급 요청의 PENDING은 하나
 * ({@code uk_coupon_dlt_log_pending_request}, 부분 유일 인덱스)다. REPLAYING은 이 인덱스에 들지 않는다(선점 뒤 재격리가 새 PENDING이
 * 될 수 있게).
 */
public interface CouponDltLogRepository extends JpaRepository<CouponDltLog, Long> {

    String SOURCE_UNIQUE = "uk_coupon_dlt_log_source";
    String PENDING_REQUEST_UNIQUE = "uk_coupon_dlt_log_pending_request";

    /** 이 메시지 위치를 이미 격리했는가(DLT 전송이 실패해 다시 격리할 때) */
    boolean existsBySourceTopicAndSourcePartitionAndSourceOffset(String sourceTopic, int sourcePartition,
                                                                 long sourceOffset);

    Optional<CouponDltLog> findFirstByCouponIdAndIssueRequestIdAndStatus(Long couponId, String issueRequestId,
                                                                         CouponDltStatus status);

    /**
     * 이 발급 요청의 PENDING 격리 기록이 있는가. 재발행 전송이 실패했을 때 선점을 PENDING으로 되돌릴 수 있는지 본다(같은 요청의 PENDING은
     * 하나). 재발행 배치의 제외는 REPLAYING까지 세는 {@link #findUnresolvedRequestIds}로 한다.
     */
    default boolean existsPendingFor(Long couponId, String issueRequestId) {
        return existsByCouponIdAndIssueRequestIdAndStatus(couponId, issueRequestId, CouponDltStatus.PENDING);
    }

    boolean existsByCouponIdAndIssueRequestIdAndStatus(Long couponId, String issueRequestId, CouponDltStatus status);

    /**
     * 쿠폰의 아직 처리되지 않은 DLT 요청 ID들: PENDING과 REPLAYING(재발행 선점 중). REPLAYING은 선점 lease가 지났든 아니든 미처리로 센다
     * (전송 결과가 확정되기 전이므로). 재발행 배치의 제외와 복구의 미처리 집계가 쓴다.
     */
    @Query("""
            select l.issueRequestId from CouponDltLog l
            where l.couponId = :couponId
              and l.status in (com.hapbang.coupon.domain.CouponDltStatus.PENDING,
                               com.hapbang.coupon.domain.CouponDltStatus.REPLAYING)
              and l.issueRequestId is not null
            """)
    List<String> findUnresolvedRequestIds(@Param("couponId") Long couponId);

    /** 아직 처리되지 않은 DLT 기록 전체 수(PENDING + REPLAYING, 쿠폰을 모르는 기록 포함) */
    @Query("""
            select count(l) from CouponDltLog l
            where l.status in (com.hapbang.coupon.domain.CouponDltStatus.PENDING,
                               com.hapbang.coupon.domain.CouponDltStatus.REPLAYING)
            """)
    long countUnresolved();

    /** 상태별 목록. 최신순 정렬은 호출자가 Pageable로 준다 */
    Page<CouponDltLog> findByStatus(CouponDltStatus status, Pageable pageable);

    long countByStatus(CouponDltStatus status);
}
