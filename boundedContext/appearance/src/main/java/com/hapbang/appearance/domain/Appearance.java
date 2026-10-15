package com.hapbang.appearance.domain;

import java.time.LocalDateTime;
import java.util.Objects;

import com.hapbang.shared.jpa.BaseEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 출연. 출연 성립으로 생긴 거래 1건이다 (게스트 1명 기준). 출연 취소 요청·확인·위약금·지급은 이 단위로 처리한다.
 * <p>
 * 출연료와 방송 예정 일시는 성립 시점 값을 그대로 둔다(스냅샷). 출연은 요청서와 모집 양쪽에서 생기므로,
 * 지급·위약금·반환을 출처와 관계없이 이 값으로 계산하기 위해서다.
 */
@Entity
@Table(name = "appearances", schema = "appearances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Appearance extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private AppearanceSourceType sourceType;

    /** 출처 출연 요청서 (요청에서 성립한 경우) */
    private Long requestId;

    /** 출처 모집 지원 (모집에서 성립한 경우) */
    private Long applicationId;

    /** 모집글 (모집에서 성립한 경우, 모집별 조회용) */
    private Long recruitId;

    private Long hostId;

    private Long guestId;

    /** 출연료 스냅샷 (HP). 지급·위약금·반환의 기준 금액 */
    private long fee;

    /** 방송 예정 일시 스냅샷. 출연 확인 기한(D+7)·출연 취소 요청 마감의 기준 */
    private LocalDateTime scheduledAt;

    @Enumerated(EnumType.STRING)
    private AppearanceStatus status;

    /** 성립 시각 */
    private LocalDateTime establishedAt;

    // 아래는 출연 확인·취소 처리에서 채운다
    private String confirmType;

    private Long penaltyAmount;

    private LocalDateTime confirmedAt;

    private LocalDateTime noShowAt;

    private LocalDateTime disputedAt;

    private LocalDateTime canceledAt;

    /** 낙관적 락 */
    @Version
    private Long version;

    private Appearance(AppearanceRequest request, LocalDateTime now) {
        this.sourceType = AppearanceSourceType.REQUEST;
        this.requestId = request.getId();
        this.hostId = request.getHostId();
        this.guestId = request.getGuestId();
        this.fee = request.getFee();
        this.scheduledAt = request.getScheduledAt();
        this.status = AppearanceStatus.ESTABLISHED;
        this.establishedAt = now;
    }

    private Appearance(AppearanceRecruit recruit, AppearanceApplication application, LocalDateTime now) {
        this.sourceType = AppearanceSourceType.RECRUIT;
        this.applicationId = application.getId();
        this.recruitId = recruit.getId();
        this.hostId = recruit.getHostId();
        this.guestId = application.getGuestId();
        this.fee = recruit.getFeePerPerson();
        this.scheduledAt = recruit.getScheduledAt();
        this.status = AppearanceStatus.ESTABLISHED;
        this.establishedAt = now;
    }

    /**
     * 수락된 출연 모집 지원으로 출연을 만든다 (출연 성립). 출연료는 모집글의 1인당 출연료다.
     * HP는 이동하지 않고 모집글의 보류를 그대로 쓴다.
     */
    public static Appearance establishFrom(AppearanceRecruit recruit, AppearanceApplication application,
                                           LocalDateTime now) {
        if (application.getStatus() != AppearanceApplicationStatus.SELECTED
                || !Objects.equals(application.getRecruitId(), recruit.getId())) {
            throw new IllegalStateException("이 모집글에서 수락된 지원으로만 출연을 만들 수 있습니다: " + application.getStatus());
        }
        return new Appearance(recruit, application, now);
    }

    /**
     * 수락된 출연 요청서로 출연을 만든다 (출연 성립). HP는 이동하지 않고 요청서의 보류를 그대로 쓴다.
     */
    public static Appearance establishFrom(AppearanceRequest request, LocalDateTime now) {
        if (request.getStatus() != AppearanceRequestStatus.ACCEPTED) {
            throw new IllegalStateException("수락된 출연 요청서로만 출연을 만들 수 있습니다: " + request.getStatus());
        }
        return new Appearance(request, now);
    }

    // ---------------------------------------------------------------------------------------------
    // 출연 취소 요청 (#231). 요청·응답 기록은 AppearanceCancelRequest에 남기고, 출연은 진행 상태만 가진다.

    /**
     * 게스트의 출연 취소 요청을 받는다 → {@code CANCEL_REQUESTED}. 재요청은 거절돼 {@code ESTABLISHED}로 돌아온 뒤에만 된다.
     *
     * @param previousCount 이 출연에 이미 보낸 취소 요청 수
     * @param maxCount      출연 1건당 최대 취소 요청 수 ({@link AppearancePolicy#maxCancelRequestCount()})
     * @throws AppearanceException 게스트가 아님({@code NOT_APPEARANCE_GUEST}), 처리 중인 요청이 있음
     *                             ({@code CANCEL_REQUEST_ALREADY_PENDING}), 성립 상태가 아님({@code APPEARANCE_NOT_CANCELABLE}),
     *                             방송 예정 일시가 지남({@code APPEARANCE_SCHEDULE_PASSED}), 횟수 초과({@code CANCEL_REQUEST_LIMIT_EXCEEDED})
     */
    public void requestCancel(Long userId, int previousCount, int maxCount, LocalDateTime now) {
        if (!isGuest(userId)) {
            throw new AppearanceException(AppearanceErrorCode.NOT_APPEARANCE_GUEST);
        }
        if (status == AppearanceStatus.CANCEL_REQUESTED) {
            throw new AppearanceException(AppearanceErrorCode.CANCEL_REQUEST_ALREADY_PENDING);
        }
        if (status != AppearanceStatus.ESTABLISHED) {
            throw new AppearanceException(AppearanceErrorCode.APPEARANCE_NOT_CANCELABLE);
        }
        requireBeforeScheduled(now);
        if (previousCount >= maxCount) {
            throw new AppearanceException(AppearanceErrorCode.CANCEL_REQUEST_LIMIT_EXCEEDED,
                    "출연 취소 요청은 출연 1건당 %d번까지 할 수 있습니다.".formatted(maxCount));
        }
        this.status = AppearanceStatus.CANCEL_REQUESTED;
    }

    /**
     * 호스트가 처리 중인 취소 요청에 응답(수락·거절)할 수 있는지 확인한다. 응답은 방송 예정 일시 전까지만 받는다.
     *
     * @throws AppearanceException 호스트가 아님({@code NOT_APPEARANCE_HOST}), 처리 중인 요청 없음({@code CANCEL_REQUEST_NOT_PENDING}),
     *                             방송 예정 일시가 지남({@code APPEARANCE_SCHEDULE_PASSED})
     */
    public void validateCancelRespondable(Long userId, LocalDateTime now) {
        if (!isHost(userId)) {
            throw new AppearanceException(AppearanceErrorCode.NOT_APPEARANCE_HOST);
        }
        if (status != AppearanceStatus.CANCEL_REQUESTED) {
            throw new AppearanceException(AppearanceErrorCode.CANCEL_REQUEST_NOT_PENDING);
        }
        requireBeforeScheduled(now);
    }

    /** 호스트가 취소 요청을 수락했다 → 합의 취소 (위약금 없음) */
    public void agreeCancel(LocalDateTime now) {
        requireStatus(AppearanceStatus.CANCEL_REQUESTED);
        this.status = AppearanceStatus.CANCELED_BY_AGREEMENT;
        this.canceledAt = now;
    }

    /** 취소 요청이 거절(호스트 거절·자동 거절)됐다 → 출연 유지 */
    public void keepAfterCancelRejected() {
        requireStatus(AppearanceStatus.CANCEL_REQUESTED);
        this.status = AppearanceStatus.ESTABLISHED;
    }

    /** 자동 거절 대상인지 (취소 요청 처리 중이고 방송 예정 일시와 같거나 지남) */
    public boolean isCancelRequestDue(LocalDateTime now) {
        return status == AppearanceStatus.CANCEL_REQUESTED && !now.isBefore(scheduledAt);
    }

    /**
     * 출연의 호스트·게스트만 볼 수 있다. 다른 회원에게는 있다는 사실도 알리지 않는다.
     *
     * @throws AppearanceException {@code APPEARANCE_NOT_FOUND}
     */
    public void validateViewableBy(Long userId) {
        if (!(isHost(userId) || isGuest(userId))) {
            throw new AppearanceException(AppearanceErrorCode.APPEARANCE_NOT_FOUND);
        }
    }

    public boolean isHost(Long userId) {
        return hostId.equals(userId);
    }

    public boolean isGuest(Long userId) {
        return guestId.equals(userId);
    }

    public boolean isFromRecruit() {
        return sourceType == AppearanceSourceType.RECRUIT;
    }

    private void requireBeforeScheduled(LocalDateTime now) {
        if (!now.isBefore(scheduledAt)) {
            throw new AppearanceException(AppearanceErrorCode.APPEARANCE_SCHEDULE_PASSED);
        }
    }

    private void requireStatus(AppearanceStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("출연 상태가 %s가 아닙니다: %s".formatted(expected, status));
        }
    }
}
