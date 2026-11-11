package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.member.out.WithdrawalBlockSource;
import com.hapbang.shared.member.dto.WithdrawalBlocksDto;

/**
 * 탈퇴 오케스트레이터 시작 조건 (#654). 출연·강의 중 하나라도 연결 코드가 없으면(Client 주소 누락 등) 그 앱의 막힌 이유를 묻지 않고
 * 탈퇴되므로 시작하지 않는다. 다른 의존성은 시작 검사 뒤에 쓰므로 비워 둔다
 */
class MemberWithdrawalUseCaseStartupTest {

    @Test
    @DisplayName("출연·강의 연결 코드가 모두 있으면 시작한다")
    void startsWithAppearanceAndLecture() {
        assertThatCode(() -> create(List.of(source("appearance"), source("lecture")))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("강의(또는 출연) 연결 코드가 빠지면 시작하지 않는다")
    void failsWhenOneIsMissing() {
        assertThatThrownBy(() -> create(List.of(source("appearance"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lecture");
        assertThatThrownBy(() -> create(List.of(source("lecture"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("appearance");
        assertThatThrownBy(() -> create(List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static MemberWithdrawalUseCase create(List<WithdrawalBlockSource> sources) {
        return new MemberWithdrawalUseCase(null, null, sources, null, null, null, null);
    }

    private static WithdrawalBlockSource source(String name) {
        return new WithdrawalBlockSource() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public WithdrawalBlocksDto blocks(long userId) {
                return WithdrawalBlocksDto.none();
            }
        };
    }
}
