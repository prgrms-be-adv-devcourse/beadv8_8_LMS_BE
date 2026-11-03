package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AppearanceWithdrawalBlockTest {

    @Test
    @DisplayName("건수는 천 단위 쉼표로 문장에 넣는다")
    void countWithComma() {
        assertThat(AppearanceWithdrawalBlock.APPEARANCE_REQUEST_SENT.message(1_234))
                .isEqualTo("응답을 기다리는 출연 요청 1,234건이 있어요. 요청을 취소한 뒤 탈퇴할 수 있어요.");
        assertThat(AppearanceWithdrawalBlock.APPEARANCE_IN_PROGRESS.message(1))
                .isEqualTo("성립된 출연 1건이 진행 중이라 탈퇴할 수 없어요. 출연이 끝나거나 취소된 뒤 탈퇴할 수 있어요.");
    }

    @Test
    @DisplayName("건수가 없는 문장은 건수와 관계없이 같다")
    void messageWithoutCount() {
        assertThat(AppearanceWithdrawalBlock.APPEARANCE_HP_PROCESSING.message(3))
                .isEqualTo("출연 HP를 처리하고 있어요. 잠시 뒤 다시 시도해 주세요.");
        assertThat(AppearanceWithdrawalBlock.APPEARANCE_HP_CHECK_REQUIRED.message(2))
                .isEqualTo("출연 HP 처리에 문제가 있어 확인하고 있어요. 확인이 끝난 뒤 탈퇴할 수 있어요.");
    }
}
