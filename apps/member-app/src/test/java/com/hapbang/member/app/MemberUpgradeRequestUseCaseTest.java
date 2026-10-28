package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.UpgradeRequestStatus;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.InfluencerUpgradeRequestRepository;
import com.hapbang.shared.member.domain.Role;

/** 저장 때 DB 제약 위반을 어떻게 바꾸는지 (동시 신청은 타이밍에 따라 앱 확인에서 막히기도 해서 여기서 따로 본다) */
@ExtendWith(MockitoExtension.class)
class MemberUpgradeRequestUseCaseTest {

    @Mock
    MemberSupport memberSupport;

    @Mock
    InfluencerUpgradeRequestRepository influencerUpgradeRequestRepository;

    @InjectMocks
    MemberUpgradeRequestUseCase useCase;

    @Test
    @DisplayName("심사 대기 1건 인덱스 위반(동시 신청)은 409 UPGRADE_REQUEST_ALREADY_PENDING")
    void request_onePendingViolation() {
        givenInfluencerWithoutPending();
        given(influencerUpgradeRequestRepository.saveAndFlush(any())).willThrow(violation(
                "duplicate key value violates unique constraint \"" + MemberUpgradeRequestUseCase.ONE_PENDING_INDEX + "\""));

        assertThatThrownBy(() -> useCase.request(1L, "사유"))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.UPGRADE_REQUEST_ALREADY_PENDING);
    }

    @Test
    @DisplayName("다른 제약 위반은 바꾸지 않고 그대로 던진다 (예상 못 한 실패 → 500)")
    void request_otherViolation() {
        givenInfluencerWithoutPending();
        given(influencerUpgradeRequestRepository.saveAndFlush(any())).willThrow(violation(
                "insert or update violates foreign key constraint \"fk_influencer_upgrade_request_on_user\""));

        assertThatThrownBy(() -> useCase.request(1L, "사유"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void givenInfluencerWithoutPending() {
        given(memberSupport.findActiveUser(1L)).willReturn(MemberFixture.member(Role.INFLUENCER));
        given(influencerUpgradeRequestRepository.existsByUserIdAndStatus(1L, UpgradeRequestStatus.PENDING))
                .willReturn(false);
    }

    private DataIntegrityViolationException violation(String databaseMessage) {
        return new DataIntegrityViolationException("could not execute statement", new SQLException(databaseMessage));
    }
}
