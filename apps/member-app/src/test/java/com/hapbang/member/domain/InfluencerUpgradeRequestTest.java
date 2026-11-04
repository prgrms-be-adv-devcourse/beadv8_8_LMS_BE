package com.hapbang.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.shared.member.domain.Role;

class InfluencerUpgradeRequestTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 10, 0);
    private static final Long ADMIN_ID = 99L;

    @Test
    @DisplayName("인플루언서가 신청하면 심사 대기가 되고 사유는 앞뒤 공백을 지워 저장한다")
    void request() {
        InfluencerUpgradeRequest request = InfluencerUpgradeRequest.request(
                MemberFixture.member(Role.INFLUENCER), "  강의 경력 3년  ", NOW);

        assertThat(request.getStatus()).isEqualTo(UpgradeRequestStatus.PENDING);
        assertThat(request.isPending()).isTrue();
        assertThat(request.getRequestReason()).isEqualTo("강의 경력 3년");
        assertThat(request.getRequestedAt()).isEqualTo(NOW);
        assertThat(request.getReviewedBy()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"USER", "VERIFIED_INFLUENCER", "ADMIN"})
    @DisplayName("인플루언서가 아니면 신청할 수 없다 (403)")
    void request_notInfluencer(Role role) {
        assertThatThrownBy(() -> InfluencerUpgradeRequest.request(MemberFixture.member(role), "사유", NOW))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.UPGRADE_NOT_ALLOWED);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("신청 사유가 없으면 400")
    void request_reasonRequired(String reason) {
        assertThatThrownBy(() -> InfluencerUpgradeRequest.request(MemberFixture.member(Role.INFLUENCER), reason, NOW))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.UPGRADE_REASON_REQUIRED);
    }

    @Test
    @DisplayName("신청 사유는 앞뒤 공백을 빼고 500자까지, 넘으면 400")
    void request_reasonLength() {
        User influencer = MemberFixture.member(Role.INFLUENCER);

        assertThat(InfluencerUpgradeRequest.request(influencer, " " + "가".repeat(500) + " ", NOW).getRequestReason())
                .hasSize(500);
        assertThatThrownBy(() -> InfluencerUpgradeRequest.request(influencer, "가".repeat(501), NOW))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_UPGRADE_REASON_LENGTH);
    }

    @Test
    @DisplayName("심사 대기 신청을 승인하면 심사한 관리자와 시각이 남는다")
    void approve() {
        InfluencerUpgradeRequest request = pending();

        request.approve(ADMIN_ID, NOW.plusDays(1));

        assertThat(request.getStatus()).isEqualTo(UpgradeRequestStatus.APPROVED);
        assertThat(request.getReviewedBy()).isEqualTo(ADMIN_ID);
        assertThat(request.getReviewedAt()).isEqualTo(NOW.plusDays(1));
    }

    @Test
    @DisplayName("심사 대기 신청을 반려하면 앞뒤 공백을 지운 사유가 남는다")
    void reject() {
        InfluencerUpgradeRequest request = pending();

        request.reject(ADMIN_ID, "  활동 내역 부족  ", NOW.plusDays(1));

        assertThat(request.getStatus()).isEqualTo(UpgradeRequestStatus.REJECTED);
        assertThat(request.getRejectionReason()).isEqualTo("활동 내역 부족");
        assertThat(request.getReviewedBy()).isEqualTo(ADMIN_ID);
        assertThat(request.getReviewedAt()).isEqualTo(NOW.plusDays(1));
    }

    @Test
    @DisplayName("반려 사유가 없거나 500자를 넘으면 400 (채널 반려와 같은 코드)")
    void reject_invalidReason() {
        InfluencerUpgradeRequest request = pending();

        assertThatThrownBy(() -> request.reject(ADMIN_ID, " ", NOW))
                .extracting("errorCode").isEqualTo(MemberErrorCode.REJECTION_REASON_REQUIRED);
        assertThatThrownBy(() -> request.reject(ADMIN_ID, "가".repeat(501), NOW))
                .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_REJECTION_REASON_LENGTH);
        assertThat(request.isPending()).isTrue();
    }

    @Test
    @DisplayName("심사 대기가 아닌 신청(승인·반려됨)은 다시 승인·반려할 수 없다 (409)")
    void review_notPending() {
        InfluencerUpgradeRequest approved = pending();
        approved.approve(ADMIN_ID, NOW);
        InfluencerUpgradeRequest rejected = pending();
        rejected.reject(ADMIN_ID, "사유", NOW);

        for (InfluencerUpgradeRequest request : new InfluencerUpgradeRequest[] {approved, rejected}) {
            assertThatThrownBy(() -> request.approve(ADMIN_ID, NOW))
                    .isInstanceOf(MemberException.class)
                    .extracting("errorCode").isEqualTo(MemberErrorCode.UPGRADE_REQUEST_REVIEW_NOT_ALLOWED);
            assertThatThrownBy(() -> request.reject(ADMIN_ID, "사유", NOW))
                    .isInstanceOf(MemberException.class)
                    .extracting("errorCode").isEqualTo(MemberErrorCode.UPGRADE_REQUEST_REVIEW_NOT_ALLOWED);
        }
    }

    private InfluencerUpgradeRequest pending() {
        return InfluencerUpgradeRequest.request(MemberFixture.member(Role.INFLUENCER), "강의 경력 3년", NOW);
    }
}
