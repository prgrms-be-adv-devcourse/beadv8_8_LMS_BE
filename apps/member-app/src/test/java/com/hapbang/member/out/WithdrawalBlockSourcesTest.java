package com.hapbang.member.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.shared.appearance.out.AppearanceApiCallException;
import com.hapbang.shared.appearance.out.AppearanceApiClient;
import com.hapbang.shared.lecture.out.LectureApiCallException;
import com.hapbang.shared.lecture.out.LectureApiClient;
import com.hapbang.shared.member.dto.WithdrawalBlockReasonDto;
import com.hapbang.shared.member.dto.WithdrawalBlocksDto;

/**
 * 출연·강의 탈퇴 막힌 이유 연결 (#654). 막힌 이유는 그대로 넘기고, 어떤 실패든 "처리 안 됨"으로 바꾼다(읽기라 되돌릴 것이 없다)
 */
class WithdrawalBlockSourcesTest {

    private static final long USER_ID = 42L;

    private final AppearanceApiClient appearanceApiClient = mock(AppearanceApiClient.class);
    private final LectureApiClient lectureApiClient = mock(LectureApiClient.class);
    private final MemberOutboundCircuitBreakers circuitBreakers = MemberOutboundCircuitBreakers.defaults();
    private final AppearanceWithdrawalBlockSource appearance =
            new AppearanceWithdrawalBlockSource(appearanceApiClient, circuitBreakers);
    private final LectureWithdrawalBlockSource lecture = new LectureWithdrawalBlockSource(lectureApiClient, circuitBreakers);

    @Test
    @DisplayName("출연·강의가 준 막힌 이유를 그대로 넘긴다")
    void passesReasons() {
        WithdrawalBlocksDto appearanceBlocks = new WithdrawalBlocksDto(List.of(
                WithdrawalBlockReasonDto.of("APPEARANCE_IN_PROGRESS", "진행 중인 출연 1건이 있어요.")));
        given(appearanceApiClient.getWithdrawalBlocks(USER_ID)).willReturn(appearanceBlocks);
        given(lectureApiClient.getWithdrawalBlocks(USER_ID)).willReturn(WithdrawalBlocksDto.none());

        assertThat(appearance.blocks(USER_ID)).isEqualTo(appearanceBlocks);
        assertThat(lecture.blocks(USER_ID).isBlocked()).isFalse();
        assertThat(appearance.name()).isEqualTo("appearance");
        assertThat(lecture.name()).isEqualTo("lecture");
    }

    @Test
    @DisplayName("Client가 실패하면(거절·장애 모두) NOT_PROCESSED로 바꾼다")
    void failureIsNotProcessed() {
        given(appearanceApiClient.getWithdrawalBlocks(USER_ID)).willThrow(mock(AppearanceApiCallException.class));
        given(lectureApiClient.getWithdrawalBlocks(USER_ID)).willThrow(mock(LectureApiCallException.class));

        assertThatThrownBy(() -> appearance.blocks(USER_ID))
                .isInstanceOfSatisfying(WithdrawalCallException.class,
                        e -> assertThat(e.getKind()).isEqualTo(WithdrawalCallException.Kind.NOT_PROCESSED));
        assertThatThrownBy(() -> lecture.blocks(USER_ID))
                .isInstanceOfSatisfying(WithdrawalCallException.class,
                        e -> assertThat(e.getKind()).isEqualTo(WithdrawalCallException.Kind.NOT_PROCESSED));
    }

    @Test
    @DisplayName("회로가 열려 있으면 Client를 부르지 않고 바로 NOT_PROCESSED다")
    void openCircuitDoesNotCall() {
        circuitBreakers.circuitBreaker(MemberOutboundCircuitBreakers.Target.APPEARANCE).transitionToOpenState();
        circuitBreakers.circuitBreaker(MemberOutboundCircuitBreakers.Target.LECTURE).transitionToOpenState();

        assertThatThrownBy(() -> appearance.blocks(USER_ID))
                .isInstanceOfSatisfying(WithdrawalCallException.class,
                        e -> assertThat(e.getKind()).isEqualTo(WithdrawalCallException.Kind.NOT_PROCESSED));
        assertThatThrownBy(() -> lecture.blocks(USER_ID))
                .isInstanceOfSatisfying(WithdrawalCallException.class,
                        e -> assertThat(e.getKind()).isEqualTo(WithdrawalCallException.Kind.NOT_PROCESSED));
        verifyNoInteractions(appearanceApiClient, lectureApiClient);
    }
}
