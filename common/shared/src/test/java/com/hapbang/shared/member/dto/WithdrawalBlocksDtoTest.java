package com.hapbang.shared.member.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WithdrawalBlocksDtoTest {

    @Test
    @DisplayName("막힌 이유는 코드와 문장이 필수이고, 풀리는 날짜는 없어도 된다")
    void reason() {
        assertThat(WithdrawalBlockReasonDto.of("APPEARANCE_IN_PROGRESS", "진행 중인 출연 1건이 있어요").availableAt())
                .isNull();
        assertThat(new WithdrawalBlockReasonDto("LECTURE_ORDER_HOLDING", "보류가 끝난 뒤 탈퇴할 수 있어요",
                LocalDate.of(2026, 12, 3)).availableAt()).isEqualTo(LocalDate.of(2026, 12, 3));
        assertThatThrownBy(() -> WithdrawalBlockReasonDto.of(null, "문장")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WithdrawalBlockReasonDto.of("CODE", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("이유가 없으면 막히지 않았고, 하나라도 있으면 막혔다")
    void blocked() {
        assertThat(WithdrawalBlocksDto.none().isBlocked()).isFalse();
        assertThat(new WithdrawalBlocksDto(List.of(WithdrawalBlockReasonDto.of("CODE", "문장"))).isBlocked()).isTrue();
    }

    @Test
    @DisplayName("이유 목록은 필수이고 null 항목을 받지 않으며, 받은 뒤 원본이 바뀌어도 그대로다")
    void reasons() {
        List<WithdrawalBlockReasonDto> source = new ArrayList<>(List.of(WithdrawalBlockReasonDto.of("CODE", "문장")));
        WithdrawalBlocksDto blocks = new WithdrawalBlocksDto(source);
        source.clear();

        assertThat(blocks.reasons()).hasSize(1);
        assertThatThrownBy(() -> new WithdrawalBlocksDto(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WithdrawalBlocksDto(Arrays.asList((WithdrawalBlockReasonDto) null)))
                .isInstanceOf(NullPointerException.class);
    }
}
