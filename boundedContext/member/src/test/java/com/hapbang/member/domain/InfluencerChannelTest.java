package com.hapbang.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InfluencerChannelTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 16, 0);

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

    private InfluencerChannel register(int followerCount) {
        return InfluencerChannel.register(user, ChannelPlatform.CHZZK, "channel-1", "테스트 채널", followerCount, NOW);
    }
}
