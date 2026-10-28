package com.hapbang.member.domain;

import java.time.LocalDateTime;

import com.hapbang.shared.jpa.BaseEntity;
import com.hapbang.shared.member.domain.Role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 인증된 인플루언서 승급 신청. 인플루언서가 사유를 적어 신청하고 관리자가 승인·반려한다 (신청 1건 = 1행, 이력이 남는다) */
@Entity
@Table(name = "influencer_upgrade_request", schema = "members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InfluencerUpgradeRequest extends BaseEntity {

    /** 신청·반려 사유 최대 길이 (앞뒤 공백 제외, 채널 반려 사유와 같은 500자) */
    public static final int REASON_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UpgradeRequestStatus status;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String requestReason;

    @Column(columnDefinition = "TEXT")
    private String rejectionReason;

    /** 심사한 관리자 ID */
    private Long reviewedBy;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    /** 승인·반려 시각 */
    private LocalDateTime reviewedAt;

    private InfluencerUpgradeRequest(User user, String requestReason, LocalDateTime now) {
        this.user = user;
        this.status = UpgradeRequestStatus.PENDING;
        this.requestReason = requestReason;
        this.requestedAt = now;
    }

    /** 신청: 인플루언서만, 사유 필수 (대기 중 1건은 UseCase 확인 + DB 인덱스) */
    public static InfluencerUpgradeRequest request(User user, String reason, LocalDateTime now) {
        if (user.getRole() != Role.INFLUENCER) {
            throw new MemberException(MemberErrorCode.UPGRADE_NOT_ALLOWED);
        }
        String requestReason = validReason(reason,
                MemberErrorCode.UPGRADE_REASON_REQUIRED, MemberErrorCode.INVALID_UPGRADE_REASON_LENGTH);
        return new InfluencerUpgradeRequest(user, requestReason, now);
    }

    /** 관리자 승인 (심사 대기만). 역할 변경·이벤트는 UseCase가 한다 */
    public void approve(Long adminId, LocalDateTime now) {
        requirePending();
        this.status = UpgradeRequestStatus.APPROVED;
        this.reviewedBy = adminId;
        this.reviewedAt = now;
    }

    /** 관리자 반려 (심사 대기만, 사유 필수) */
    public void reject(Long adminId, String reason, LocalDateTime now) {
        requirePending();
        this.rejectionReason = validReason(reason,
                MemberErrorCode.REJECTION_REASON_REQUIRED, MemberErrorCode.INVALID_REJECTION_REASON_LENGTH);
        this.status = UpgradeRequestStatus.REJECTED;
        this.reviewedBy = adminId;
        this.reviewedAt = now;
    }

    public boolean isPending() {
        return status == UpgradeRequestStatus.PENDING;
    }

    private void requirePending() {
        if (!isPending()) {
            throw new MemberException(MemberErrorCode.UPGRADE_REQUEST_REVIEW_NOT_ALLOWED);
        }
    }

    /** 앞뒤 공백을 지운 사유. 비었거나 500자를 넘으면 해당 오류 */
    private static String validReason(String reason, MemberErrorCode required, MemberErrorCode tooLong) {
        if (reason == null || reason.isBlank()) {
            throw new MemberException(required);
        }
        String stripped = reason.strip();
        if (stripped.length() > REASON_MAX) {
            throw new MemberException(tooLong);
        }
        return stripped;
    }
}
