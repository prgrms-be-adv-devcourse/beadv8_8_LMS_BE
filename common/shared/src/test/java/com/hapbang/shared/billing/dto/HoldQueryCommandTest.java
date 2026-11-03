package com.hapbang.shared.billing.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.hapbang.shared.billing.domain.BillingReferenceType;

// 보류·지급 조회 요청(#577)이 요청 구조(참조 유형·개수·빈 ID)만 검사하는지 검증한다.
class HoldQueryCommandTest {

    @Test
    @DisplayName("보류할 수 있는 참조 유형과 1~500개의 참조 ID를 받고, 목록은 바꿀 수 없게 복사한다")
    void valid() {
        List<Long> ids = new ArrayList<>(LongStream.rangeClosed(1, HoldQueryCommand.MAX_REFERENCE_IDS).boxed().toList());

        HoldQueryCommand command = new HoldQueryCommand(BillingReferenceType.LECTURE_ORDER_ITEM, ids);
        ids.clear();

        assertThat(command.referenceIds()).hasSize(500);
        assertThatThrownBy(() -> command.referenceIds().add(1L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @EnumSource(value = BillingReferenceType.class,
            names = {"LECTURE_ORDER_ITEM", "APPEARANCE_REQUEST", "APPEARANCE_RECRUIT"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("보류할 수 없는 참조 유형은 조회할 수 없다")
    void notHoldableReferenceType(BillingReferenceType referenceType) {
        assertThatThrownBy(() -> new HoldQueryCommand(referenceType, List.of(1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("참조 유형·목록이 없거나, 0개·501개이거나, 빈 ID가 있으면 거절한다")
    void invalidStructure() {
        assertThatThrownBy(() -> new HoldQueryCommand(null, List.of(1L))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HoldQueryCommand(BillingReferenceType.APPEARANCE_REQUEST, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HoldQueryCommand(BillingReferenceType.APPEARANCE_REQUEST, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HoldQueryCommand(BillingReferenceType.APPEARANCE_REQUEST,
                LongStream.rangeClosed(1, 501).boxed().toList())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HoldQueryCommand(BillingReferenceType.APPEARANCE_REQUEST,
                Arrays.asList(1L, null))).isInstanceOf(NullPointerException.class);
    }
}
