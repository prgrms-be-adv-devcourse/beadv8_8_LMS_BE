package com.hapbang.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class InfluencerChannelTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 16, 0);
    private static final Long ADMIN_ID = 99L;

    private final User user = User.join("tester1@example.com", "tester1");

    @Test
    @DisplayName("팔로워 10명으로 등록하면 바로 승인되고 심사한 관리자는 없다")
    void register_approved() {
        InfluencerChannel channel = register(10);

        assertThat(channel.getVerificationStatus()).isEqualTo(ChannelVerificationStatus.APPROVED);
        assertThat(channel.isApproved()).isTrue();
        assertThat(channel.getVerifiedAt()).isEqualTo(NOW);
        assertThat(channel.getVerifiedBy()).isNull();
        assertThat(channel.getChannelUrl()).isEqualTo("https://chzzk.naver.com/channel-1");
    }

    @Test
    @DisplayName("팔로워 9명으로 등록하면 등록만 된다")
    void register_registered() {
        InfluencerChannel channel = register(9);

        assertThat(channel.getVerificationStatus()).isEqualTo(ChannelVerificationStatus.REGISTERED);
        assertThat(channel.isApproved()).isFalse();
        assertThat(channel.getFollowerCount()).isEqualTo(9);
    }

    @Test
    @DisplayName("등록만 된 채널이 승인 요청할 때 팔로워가 기준을 넘었으면 바로 승인된다")
    void requestApproval_approved() {
        InfluencerChannel channel = register(5);

        channel.requestApproval(12, NOW.plusDays(3));

        assertThat(channel.getVerificationStatus()).isEqualTo(ChannelVerificationStatus.APPROVED);
        assertThat(channel.getFollowerCount()).isEqualTo(12);
        assertThat(channel.getVerifiedAt()).isEqualTo(NOW.plusDays(3));
    }

    @Test
    @DisplayName("승인 요청할 때 아직 기준 미달이면 관리자 심사 대기가 된다")
    void requestApproval_pending() {
        InfluencerChannel channel = register(5);

        channel.requestApproval(7, NOW.plusDays(1));

        assertThat(channel.getVerificationStatus()).isEqualTo(ChannelVerificationStatus.PENDING);
        assertThat(channel.getRequestedAt()).isEqualTo(NOW.plusDays(1));
        assertThat(channel.getFollowerCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("이미 심사 대기이거나 승인된 채널은 승인 요청할 수 없다")
    void requestApproval_notAllowed() {
        InfluencerChannel pending = register(5);
        pending.requestApproval(5, NOW);
        InfluencerChannel approved = register(10);

        assertThatThrownBy(() -> pending.requestApproval(5, NOW))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.CHANNEL_APPROVAL_NOT_ALLOWED);
        assertThatThrownBy(() -> approved.requestApproval(20, NOW))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.CHANNEL_APPROVAL_NOT_ALLOWED);
    }

    @Test
    @DisplayName("팔로워 기준 시각은 승인 요청 전이면 등록 시각, 요청 후면 요청 시각")
    void followerCountAt() {
        InfluencerChannel channel = register(5);
        org.springframework.test.util.ReflectionTestUtils.setField(channel, "createdAt", NOW.minusDays(3));
        assertThat(channel.followerCountAt()).isEqualTo(NOW.minusDays(3));

        channel.requestApproval(7, NOW);
        assertThat(channel.followerCountAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("등록·반려 상태만 승인 요청할 수 있다")
    void canRequestApproval() {
        assertThat(register(5).canRequestApproval()).isTrue();
        assertThat(register(10).canRequestApproval()).isFalse();
    }

    @Test
    @DisplayName("심사 대기 채널을 관리자가 승인하면 승인한 관리자와 시각이 남는다")
    void approve() {
        InfluencerChannel channel = pending();

        channel.approve(ADMIN_ID, NOW.plusDays(1));

        assertThat(channel.getVerificationStatus()).isEqualTo(ChannelVerificationStatus.APPROVED);
        assertThat(channel.getVerifiedBy()).isEqualTo(ADMIN_ID);
        assertThat(channel.getVerifiedAt()).isEqualTo(NOW.plusDays(1));
    }

    @Test
    @DisplayName("심사 대기 채널을 반려하면 앞뒤 공백을 지운 사유가 남는다")
    void reject() {
        InfluencerChannel channel = pending();

        channel.reject(ADMIN_ID, "  방송 시간 부족  ", NOW.plusDays(1));

        assertThat(channel.getVerificationStatus()).isEqualTo(ChannelVerificationStatus.REJECTED);
        assertThat(channel.getRejectionReason()).isEqualTo("방송 시간 부족");
        assertThat(channel.getVerifiedBy()).isEqualTo(ADMIN_ID);
        assertThat(channel.getVerifiedAt()).isEqualTo(NOW.plusDays(1));
    }

    @Test
    @DisplayName("반려 사유는 앞뒤 공백을 빼고 500자까지 되고, 넘으면 400")
    void reject_reasonLength() {
        InfluencerChannel channel = pending();
        assertThatThrownBy(() -> channel.reject(ADMIN_ID, "가".repeat(501), NOW))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_REJECTION_REASON_LENGTH);

        channel.reject(ADMIN_ID, " " + "가".repeat(500) + " ", NOW);

        assertThat(channel.getRejectionReason()).hasSize(500);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("반려 사유가 없으면 400")
    void reject_reasonRequired(String reason) {
        InfluencerChannel channel = pending();

        assertThatThrownBy(() -> channel.reject(ADMIN_ID, reason, NOW))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.REJECTION_REASON_REQUIRED);
    }

    @Test
    @DisplayName("심사 대기가 아닌 채널(등록만 됨·승인·반려)은 승인·반려할 수 없다")
    void review_notPending() {
        InfluencerChannel registered = register(5);
        InfluencerChannel approved = register(10);
        InfluencerChannel rejected = pending();
        rejected.reject(ADMIN_ID, "사유", NOW);

        for (InfluencerChannel channel : List.of(registered, approved, rejected)) {
            assertThatThrownBy(() -> channel.approve(ADMIN_ID, NOW))
                    .isInstanceOf(MemberException.class)
                    .extracting("errorCode").isEqualTo(MemberErrorCode.CHANNEL_REVIEW_NOT_ALLOWED);
            assertThatThrownBy(() -> channel.reject(ADMIN_ID, "사유", NOW))
                    .isInstanceOf(MemberException.class)
                    .extracting("errorCode").isEqualTo(MemberErrorCode.CHANNEL_REVIEW_NOT_ALLOWED);
        }
    }

    @Test
    @DisplayName("반려된 채널은 다시 승인 요청할 수 있고, 사유가 지워진다")
    void requestApproval_afterReject() {
        InfluencerChannel channel = pending();
        channel.reject(ADMIN_ID, "방송 시간 부족", NOW);

        channel.requestApproval(7, NOW.plusDays(2));

        assertThat(channel.getVerificationStatus()).isEqualTo(ChannelVerificationStatus.PENDING);
        assertThat(channel.getRejectionReason()).isNull();
    }

    private InfluencerChannel register(int followerCount) {
        return InfluencerChannel.register(user, ChannelPlatform.CHZZK, "channel-1", "테스트 채널", followerCount, NOW);
    }

    /** 팔로워 미달로 승인 요청해 심사 대기가 된 채널 */
    private InfluencerChannel pending() {
        InfluencerChannel channel = register(5);
        channel.requestApproval(5, NOW);
        return channel;
    }
}
