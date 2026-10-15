package com.hapbang.appearance.domain;

import java.time.LocalDateTime;

import com.hapbang.shared.appearance.dto.AppearanceCancelRequestDto;
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
 * 출연 취소 요청. 출연이 성립한 뒤 게스트가 사유를 적어 보낸 취소 요청 1건이다 (출연당 최대 2건, ISSUE.md APR-31).
 * <ul>
 *     <li>요청할 수 있는지(게스트·성립 상태·방송 예정 일시 전·횟수)는 출연({@link Appearance#requestCancel})이 확인하고,
 *     이 엔티티는 요청·응답 기록(순번·사유·거절 사유·시각)을 남긴다. 요청 시각은 {@code createdAt}이다.</li>
 *     <li>게스트는 보낸 요청을 철회할 수 없다 (K3).</li>
 * </ul>
 */
@Entity
@Table(name = "cancel_requests", schema = "appearances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppearanceCancelRequest extends BaseEntity {

    /** 취소 사유·거절 사유 최대 길이 (DB 컬럼 VARCHAR(500)) */
    public static final int REASON_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long appearanceId;

    private Long hostId;

    private Long guestId;

    /** 이 출연에서 몇 번째 취소 요청인지 (1부터) */
    private int sequence;

    /** 취소 사유 (게스트, 필수) */
    private String reason;

    @Enumerated(EnumType.STRING)
    private AppearanceCancelRequestStatus status;

    /** 거절 사유 (호스트, 선택). 자동 거절이면 비어 있다 */
    private String rejectReason;

    /** 호스트 응답(수락·거절) 또는 자동 거절 시각 */
    private LocalDateTime respondedAt;

    /** 낙관적 락 */
    @Version
    private Long version;

    private AppearanceCancelRequest(Appearance appearance, int sequence, String reason) {
        this.appearanceId = appearance.getId();
        this.hostId = appearance.getHostId();
        this.guestId = appearance.getGuestId();
        this.sequence = sequence;
        this.reason = reason;
        this.status = AppearanceCancelRequestStatus.REQUESTED;
    }

    /**
     * 게스트가 출연 취소를 요청한다. 출연은 {@code CANCEL_REQUESTED}가 된다.
     *
     * @param previousCount 이 출연에 이미 보낸 취소 요청 수
     * @throws AppearanceException 사유 없음·500자 초과({@code INVALID_REQUEST}), 출연 쪽 확인({@link Appearance#requestCancel})
     */
    public static AppearanceCancelRequest request(Appearance appearance, Long userId, String reason,
                                                  int previousCount, AppearancePolicy policy, LocalDateTime now) {
        String trimmed = requireReason(reason);
        appearance.requestCancel(userId, previousCount, policy.maxCancelRequestCount(), now);
        return new AppearanceCancelRequest(appearance, previousCount + 1, trimmed);
    }

    /** 호스트가 수락했다 (합의 취소). 출연 쪽 확인은 호출한 쪽이 출연으로 먼저 한다 */
    public void accept(LocalDateTime now) {
        requirePending();
        this.status = AppearanceCancelRequestStatus.ACCEPTED;
        this.respondedAt = now;
    }

    /**
     * 호스트가 거절했다. 거절 사유는 선택이다.
     *
     * @throws AppearanceException 거절 사유 500자 초과({@code INVALID_REQUEST})
     */
    public void reject(String rejectReason, LocalDateTime now) {
        requirePending();
        String trimmed = null;
        if (rejectReason != null && !rejectReason.isBlank()) {
            trimmed = rejectReason.strip();
        }
        if (trimmed != null && trimmed.length() > REASON_MAX) {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST,
                    "거절 사유는 %d자 이하여야 합니다.".formatted(REASON_MAX));
        }
        this.status = AppearanceCancelRequestStatus.REJECTED;
        this.rejectReason = trimmed;
        this.respondedAt = now;
    }

    /** 방송 예정 일시까지 호스트가 응답하지 않아 자동으로 거절한다 */
    public void autoReject(LocalDateTime now) {
        requirePending();
        this.status = AppearanceCancelRequestStatus.AUTO_REJECTED;
        this.respondedAt = now;
    }

    public boolean isPending() {
        return status == AppearanceCancelRequestStatus.REQUESTED;
    }

    /**
     * 다른 모듈에 넘기는 취소 요청 정보 (알림 이벤트). 취소·거절 사유는 담지 않는다.
     *
     * @param title       출처 요청서·모집글 제목
     * @param scheduledAt 방송 예정 일시
     */
    public AppearanceCancelRequestDto toDto(String title, LocalDateTime scheduledAt) {
        return new AppearanceCancelRequestDto(id, appearanceId, title, hostId, guestId, sequence, scheduledAt);
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST, "취소 사유를 입력해 주세요.");
        }
        String trimmed = reason.strip();
        if (trimmed.length() > REASON_MAX) {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST,
                    "취소 사유는 %d자 이하여야 합니다.".formatted(REASON_MAX));
        }
        return trimmed;
    }

    private void requirePending() {
        if (!isPending()) {
            throw new AppearanceException(AppearanceErrorCode.CANCEL_REQUEST_NOT_PENDING);
        }
    }
}
