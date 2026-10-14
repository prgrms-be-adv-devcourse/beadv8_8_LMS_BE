package com.hapbang.member.domain;

import java.time.LocalDateTime;

import com.hapbang.shared.jpa.BaseEntity;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 인플루언서 채널. 방송 플랫폼 연동으로 소유를 확인하고, 팔로워 기준을 넘으면 바로 승인 */
@Entity
@Table(name = "influencer_channel", schema = "members",
        uniqueConstraints = @UniqueConstraint(columnNames = {"platform", "channel_identifier"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InfluencerChannel extends BaseEntity {

    /** 자동 승인 기준 (정책: 팔로워 10명 이상) */
    public static final int AUTO_APPROVE_FOLLOWER_COUNT = 10;

    /** 반려 사유 최대 길이 (앞뒤 공백 제외, 2026-10-14 결정: 출연 거절 사유와 같은 500자) */
    public static final int REJECTION_REASON_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    /** 방송 플랫폼 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChannelPlatform platform;

    /** 플랫폼이 채널마다 붙인 고유 ID */
    @Column(nullable = false)
    private String channelIdentifier;

    /** 채널 이름 (등록 시점) */
    @Column(nullable = false)
    private String channelName;

    /** 채널 주소 */
    @Column(nullable = false)
    private String channelUrl;

    /** 등록·승인 요청 시점의 팔로워 수 */
    @Column(nullable = false)
    private int followerCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChannelVerificationStatus verificationStatus;

    /** 승인 요청 시각 */
    private LocalDateTime requestedAt;

    /** 승인·반려 시각 */
    private LocalDateTime verifiedAt;

    /** 심사한 관리자 ID (자동 승인이면 null) */
    private Long verifiedBy;

    @Column(columnDefinition = "TEXT")
    private String rejectionReason;

    private LocalDateTime deletedAt;

    private InfluencerChannel(User user, ChannelPlatform platform, String channelIdentifier, String channelName) {
        this.user = user;
        this.platform = platform;
        this.channelIdentifier = channelIdentifier;
        this.channelName = channelName;
        this.channelUrl = platform.channelUrl(channelIdentifier);
    }

    /** 채널 등록. 팔로워 기준을 넘으면 바로 승인, 아니면 등록만 */
    public static InfluencerChannel register(User user, ChannelPlatform platform, String channelIdentifier,
                                             String channelName, int followerCount, LocalDateTime now) {
        InfluencerChannel channel = new InfluencerChannel(user, platform, channelIdentifier, channelName);
        channel.followerCount = followerCount;
        if (meetsFollowerCount(followerCount)) {
            channel.approveAutomatically(now);
        } else {
            channel.verificationStatus = ChannelVerificationStatus.REGISTERED;
        }
        return channel;
    }

    /** 승인 요청 (등록만 됐거나 반려된 채널). 팔로워 기준을 넘으면 바로 승인, 아니면 관리자 심사 대기 */
    public void requestApproval(int followerCount, LocalDateTime now) {
        if (!canRequestApproval()) {
            throw new MemberException(MemberErrorCode.CHANNEL_APPROVAL_NOT_ALLOWED);
        }
        this.followerCount = followerCount;
        this.requestedAt = now;
        // 반려 후 다시 요청하면 지난 처리 정보를 지운다 (심사 대기에는 처리 정보가 없다)
        this.verifiedAt = null;
        this.verifiedBy = null;
        this.rejectionReason = null;
        if (meetsFollowerCount(followerCount)) {
            approveAutomatically(now);
        } else {
            this.verificationStatus = ChannelVerificationStatus.PENDING;
        }
    }

    /** 관리자 승인 (심사 대기만). 승인한 관리자 ID를 남긴다 */
    public void approve(Long adminId, LocalDateTime now) {
        requirePending();
        this.verificationStatus = ChannelVerificationStatus.APPROVED;
        this.verifiedAt = now;
        this.verifiedBy = adminId;
    }

    /** 관리자 반려 (심사 대기만). 사유 필수(최대 500자), 회원은 다시 승인 요청할 수 있다 */
    public void reject(Long adminId, String reason, LocalDateTime now) {
        requirePending();
        if (reason == null || reason.isBlank()) {
            throw new MemberException(MemberErrorCode.REJECTION_REASON_REQUIRED);
        }
        String stripped = reason.strip();
        if (stripped.length() > REJECTION_REASON_MAX) {
            throw new MemberException(MemberErrorCode.INVALID_REJECTION_REASON_LENGTH);
        }
        this.verificationStatus = ChannelVerificationStatus.REJECTED;
        this.verifiedAt = now;
        this.verifiedBy = adminId;
        this.rejectionReason = stripped;
    }

    /** 등록만 됐거나 반려된 채널만 승인 요청할 수 있다 */
    public boolean canRequestApproval() {
        return verificationStatus == ChannelVerificationStatus.REGISTERED
                || verificationStatus == ChannelVerificationStatus.REJECTED;
    }

    /**
     * 팔로워 수를 조회한 시각. 팔로워 수는 등록·승인 요청 때만 바뀌므로 승인 요청 시각, 없으면 등록 시각.
     * TODO(배치): 팔로워 수를 주기적으로 갱신하게 되면 전용 컬럼(follower_count_updated_at)으로 바꾼다
     */
    public LocalDateTime followerCountAt() {
        return requestedAt != null ? requestedAt : getCreatedAt();
    }

    public boolean isApproved() {
        return verificationStatus == ChannelVerificationStatus.APPROVED;
    }

    private void requirePending() {
        if (verificationStatus != ChannelVerificationStatus.PENDING) {
            throw new MemberException(MemberErrorCode.CHANNEL_REVIEW_NOT_ALLOWED);
        }
    }

    private static boolean meetsFollowerCount(int followerCount) {
        return followerCount >= AUTO_APPROVE_FOLLOWER_COUNT;
    }

    /** 자동 승인은 심사한 관리자가 없다 (verifiedBy = null) */
    private void approveAutomatically(LocalDateTime now) {
        this.verificationStatus = ChannelVerificationStatus.APPROVED;
        this.verifiedAt = now;
        this.verifiedBy = null;
    }
}
