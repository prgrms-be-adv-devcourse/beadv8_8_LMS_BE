package com.hapbang.coupon.app;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStore.AllowResult;
import com.hapbang.coupon.app.port.LectureCatalog;
import com.hapbang.coupon.app.port.LectureCatalog.LectureSnapshot;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.CouponRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 쿠폰 UseCase가 함께 쓰는 조회와 발급 접수 열기.
 * <p>
 * 발급 저장소(Redis)를 여는 쪽(열기·승인·재개)과 막는 쪽(중단·종료)이 동시에 돌아도 DB가 OPEN이 아닌데 Redis만 열려 있지 않도록,
 * Redis 상태 쓰기는 모두 쿠폰 DB version을 fence로 넘긴다({@link CouponIssueStore}).
 * <ul>
 *     <li>여는 쪽: (열기만) Redis 준비(접수 불가) → DB OPEN 커밋(version v) → {@link #allowIssue}(v). 접수는 DB OPEN 커밋 뒤에만 열린다.</li>
 *     <li>막는 쪽: 지금 DB version으로 Redis를 막고 확인 → DB 전이 커밋(version v') → v'로 한 번 더 막는다.</li>
 * </ul>
 * Redis는 열기를 저장된 fence보다 클 때만, 막기를 같거나 클 때만 반영한다. 그래서 늦게 도착한 열기는 그 뒤에 커밋된 막기를 덮지 못하고
 * (막는 쪽의 첫 차단이 같은 version의 열기를 막는다), 늦게 도착한 막기도 그 뒤에 커밋된 열기를 덮지 못한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponSupport {

    /** Redis 발급 상태(issue-control status) */
    static final String REDIS_SUSPENDED = "SUSPENDED";
    static final String REDIS_CLOSED = "CLOSED";

    /** 쿠폰 목록 조회 상한. 1차 규모라 페이지 없이 앞(최신순) 100건만 돌려준다 */
    static final int MAX_LIST_SIZE = 100;
    static final Limit LIST_LIMIT = Limit.of(MAX_LIST_SIZE);

    private final CouponRepository couponRepository;
    private final LectureCatalog lectureCatalog;
    private final CouponIssueStore couponIssueStore;

    public Coupon findCoupon(long couponId) {
        return couponRepository.findById(couponId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));
    }

    /** 신청자 본인의 쿠폰인가. 아니면 403 */
    public void requireOwner(Coupon coupon, long memberId) {
        if (!coupon.isOwnedBy(memberId)) {
            throw new CouponException(CouponErrorCode.NOT_COUPON_OWNER);
        }
    }

    /** 지금 상태가 이 중 하나인가. 아니면 409 */
    public void requireStatus(Coupon coupon, CouponStatus... allowed) {
        for (CouponStatus status : allowed) {
            if (coupon.getStatus() == status) {
                return;
            }
        }
        throw new CouponException(CouponErrorCode.INVALID_COUPON_STATUS);
    }

    /**
     * 강의 조회(강의 앱 내부 API). 트랜잭션 밖에서 부른다. 강의가 없으면 404이고, 강의 앱에 닿지 못하면
     * {@link com.hapbang.coupon.app.port.LectureCatalogUnavailableException}이 그대로 나간다(503).
     */
    public LectureSnapshot findLecture(long lectureId) {
        return lectureCatalog.findLecture(lectureId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.LECTURE_NOT_FOUND));
    }

    /**
     * 쿠폰을 읽고 이 트랜잭션 커밋 때 version을 올린다(상태를 바꾸지 않아도). 트랜잭션 안의 첫 조회로 부른다.
     * 커밋 뒤 엔티티의 version이 {@link #allowIssue}에 넘길 fence다.
     */
    public Coupon findCouponIncrementingVersion(long couponId) {
        return couponRepository.findWithVersionIncrementById(couponId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));
    }

    /**
     * DB OPEN 커밋 뒤 Redis 접수를 연다. {@code version}은 그 커밋의 쿠폰 version(fence)이다.
     * <p>
     * 더 새 쓰기가 있어 반영되지 않으면 DB를 다시 읽는다. 더 새 version으로 OPEN이면 그 열기가 Redis를 맡으므로 성공이고,
     * 아니면(그 사이 중단·종료됐거나 막는 중) 409다. Redis에서 이미 종료(CLOSED)면 409이고, 준비 기록이 없거나 Redis에 닿지 못하면
     * {@link com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException}(503)이 그대로 나간다. 어느 실패든 Redis는 접수 불가로
     * 남는다(안전한 쪽). DB가 OPEN이면 같은 요청을 다시 보내 version을 올려 다시 연다.
     */
    public void allowIssue(long couponId, long version) {
        AllowResult result;
        try {
            result = couponIssueStore.allowIssue(couponId, version);
        } catch (IllegalStateException exception) {
            // Redis에서 이미 종료됐다(그 사이 종료 요청이 Redis를 막았다)
            throw new CouponException(CouponErrorCode.INVALID_COUPON_STATUS, "이미 종료된 발급은 다시 열 수 없습니다.",
                    exception);
        }
        if (result == AllowResult.APPLIED) {
            return;
        }
        Coupon current = findCoupon(couponId);
        if (current.getStatus() == CouponStatus.OPEN && current.getVersion() > version) {
            log.info("더 새 발급 열기가 있어 Redis 접수 열기를 그쪽에 맡겼다 couponId={}, version={}, currentVersion={}",
                    couponId, version, current.getVersion());
            return;
        }
        log.warn("발급을 여는 사이 더 새 Redis 쓰기가 있어 접수를 열지 않았다 couponId={}, version={}, status={}, currentVersion={}",
                couponId, version, current.getStatus(), current.getVersion());
        throw new CouponException(CouponErrorCode.INVALID_COUPON_STATUS, "발급을 여는 사이 쿠폰 상태가 바뀌었습니다.");
    }
}
