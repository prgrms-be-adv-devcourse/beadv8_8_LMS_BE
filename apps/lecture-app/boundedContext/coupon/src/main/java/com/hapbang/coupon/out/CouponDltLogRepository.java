package com.hapbang.coupon.out;

import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.coupon.domain.CouponDltStatus;

/**
 * DLT 격리 기록 저장소. 같은 메시지 위치는 한 번({@code uk_coupon_dlt_log_source}), 같은 발급 요청의 PENDING은 하나
 * ({@code uk_coupon_dlt_log_pending_request}, 부분 유일 인덱스)다. REPLAYING은 이 인덱스에 들지 않는다(선점 뒤 재격리가 새 PENDING이
 * 될 수 있게).
 * <p>
 * 미처리 조회는 {@link CouponDltLogRepositoryCustom}(QueryDSL)이 맡는다. {@code @Primary}는 조회 계약({@code CouponDltLogReader}) 주입이
 * 같은 계약을 구현한 QueryDSL 구현체 빈이 아닌 이 저장소를 고르게 한다.
 */
@Primary
public interface CouponDltLogRepository extends JpaRepository<CouponDltLog, Long>, CouponDltLogRepositoryCustom {

    String SOURCE_UNIQUE = "uk_coupon_dlt_log_source";
    String PENDING_REQUEST_UNIQUE = "uk_coupon_dlt_log_pending_request";

    /** 이 메시지 위치를 이미 격리했는가(DLT 전송이 실패해 다시 격리할 때) */
    boolean existsBySourceTopicAndSourcePartitionAndSourceOffset(String sourceTopic, int sourcePartition,
                                                                 long sourceOffset);

    Optional<CouponDltLog> findFirstByCouponIdAndIssueRequestIdAndStatus(Long couponId, String issueRequestId,
                                                                         CouponDltStatus status);

    /**
     * 이 발급 요청의 PENDING 격리 기록이 있는가. 재발행 전송이 실패했을 때 선점을 PENDING으로 되돌릴 수 있는지 본다(같은 요청의 PENDING은
     * 하나). 재발행 배치의 제외는 REPLAYING까지 세는 {@code findUnresolvedRequestIds}로 한다.
     */
    default boolean existsPendingFor(Long couponId, String issueRequestId) {
        return existsByCouponIdAndIssueRequestIdAndStatus(couponId, issueRequestId, CouponDltStatus.PENDING);
    }

    boolean existsByCouponIdAndIssueRequestIdAndStatus(Long couponId, String issueRequestId, CouponDltStatus status);

    /** 상태별 목록. 최신순 정렬은 호출자가 Pageable로 준다 */
    Page<CouponDltLog> findByStatus(CouponDltStatus status, Pageable pageable);

    long countByStatus(CouponDltStatus status);
}
