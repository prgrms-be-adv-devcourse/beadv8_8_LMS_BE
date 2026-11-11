package com.hapbang.shared.billing.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;

import tools.jackson.databind.json.JsonMapper;

/**
 * 지급 요청 출처 제목 (#873, BD-163). 제목은 앞뒤 공백을 지우고 빈 값은 null로 담으며 길이는 검사하지 않는다.
 * 기존 생성자·팩토리는 제목 없이(null) 만들고, 제목 필드가 없는 옛 JSON도 null로 읽는다.
 * 기본 {@link JsonMapper}(Jackson 3 기본값)로 읽어 가장 엄격한 경우를 본다.
 */
class BillingSourceTitleTest {

    private static final BillingReferenceType REQUEST = BillingReferenceType.APPEARANCE_REQUEST;
    private static final SettlementSourceType SOURCE = SettlementSourceType.APPEARANCE_REQUEST;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("기존 생성자·팩토리로 만든 지급·위약금 요청은 출처 제목이 null이다")
    void existingSignaturesHaveNoTitle() {
        assertThat(PayoutCommand.full(1L, REQUEST, 7L, "P1", 2L, SOURCE, 7L).sourceTitle()).isNull();
        assertThat(PayoutCommand.partial(1L, REQUEST, 7L, "P1", 2L, SOURCE, 7L, 1_000L).sourceTitle()).isNull();
        assertThat(new PayoutCommand(1L, REQUEST, 7L, "P1", 2L, SOURCE, 7L, 1_000L).sourceTitle()).isNull();
        assertThat(new PenaltyCommand(1L, REQUEST, 7L, "C1", 2L, SOURCE, 7L, 7_000L, 3_000L).sourceTitle()).isNull();
    }

    @Test
    @DisplayName("제목을 넘기면 앞뒤 공백을 지워 담는다")
    void titleIsStripped() {
        assertThat(PayoutCommand.full(1L, REQUEST, 7L, "P1", 2L, SOURCE, 7L, "  합방 출연  ").sourceTitle())
                .isEqualTo("합방 출연");
        assertThat(PayoutCommand.partial(1L, REQUEST, 7L, "P1", 2L, SOURCE, 7L, 1_000L, "합방 출연 ").sourceTitle())
                .isEqualTo("합방 출연");
        assertThat(new PenaltyCommand(1L, REQUEST, 7L, "C1", 2L, SOURCE, 7L, 7_000L, 3_000L, " 합방 출연")
                .sourceTitle()).isEqualTo("합방 출연");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\n"})
    @DisplayName("제목이 없거나 공백뿐이면 null로 담는다")
    void blankTitleIsNull(String title) {
        assertThat(PayoutCommand.full(1L, REQUEST, 7L, "P1", 2L, SOURCE, 7L, title).sourceTitle()).isNull();
        assertThat(new PenaltyCommand(1L, REQUEST, 7L, "C1", 2L, SOURCE, 7L, 7_000L, 3_000L, title).sourceTitle())
                .isNull();
    }

    @Test
    @DisplayName("요청의 제목 길이는 검사·변경하지 않는다(지급을 거절하지 않고, 이벤트용 상한은 지급 완료 이벤트가 정한다)")
    void titleLengthIsNotChangedInRequest() {
        String longTitle = "가".repeat(1_000);

        assertThat(PayoutCommand.full(1L, REQUEST, 7L, "P1", 2L, SOURCE, 7L, longTitle).sourceTitle())
                .isEqualTo(longTitle);
        assertThat(new PenaltyCommand(1L, REQUEST, 7L, "C1", 2L, SOURCE, 7L, 7_000L, 3_000L, longTitle)
                .sourceTitle()).isEqualTo(longTitle);
    }

    @Test
    @DisplayName("제목 필드가 없는 옛 요청 JSON은 제목 null로 읽는다")
    void oldJsonWithoutTitle() {
        assertThat(jsonMapper.readValue("""
                {"payerUserId":1,"referenceType":"APPEARANCE_REQUEST","referenceId":7,"payoutRequestId":"P1",
                 "receiverUserId":2,"sourceType":"APPEARANCE_REQUEST","sourceId":7,"amountHp":null}
                """, PayoutCommand.class)).isEqualTo(PayoutCommand.full(1L, REQUEST, 7L, "P1", 2L, SOURCE, 7L));
        assertThat(jsonMapper.readValue("""
                {"payerUserId":1,"referenceType":"APPEARANCE_REQUEST","referenceId":7,"cancellationId":"C1",
                 "receiverUserId":2,"sourceType":"APPEARANCE_REQUEST","sourceId":7,
                 "releaseAmountHp":7000,"payoutAmountHp":3000}
                """, PenaltyCommand.class))
                .isEqualTo(new PenaltyCommand(1L, REQUEST, 7L, "C1", 2L, SOURCE, 7L, 7_000L, 3_000L));
    }

    @Test
    @DisplayName("제목이 없으면 요청 JSON에 sourceTitle을 쓰지 않는다(옛 지갑이 받는 본문과 같다)")
    void noTitleNotWritten() {
        assertThat(jsonMapper.writeValueAsString(PayoutCommand.full(1L, REQUEST, 7L, "P1", 2L, SOURCE, 7L)))
                .doesNotContain("sourceTitle");
        assertThat(jsonMapper.writeValueAsString(
                new PenaltyCommand(1L, REQUEST, 7L, "C1", 2L, SOURCE, 7L, 7_000L, 3_000L)))
                .doesNotContain("sourceTitle");
    }

    @Test
    @DisplayName("제목은 요청 JSON의 sourceTitle로 쓰고 읽는다")
    void titleRoundTrip() {
        PayoutCommand payout = PayoutCommand.full(1L, REQUEST, 7L, "P1", 2L, SOURCE, 7L, "합방 출연");
        PenaltyCommand penalty = new PenaltyCommand(1L, REQUEST, 7L, "C1", 2L, SOURCE, 7L, 7_000L, 3_000L, "합방 출연");

        assertThat(jsonMapper.writeValueAsString(payout)).contains("\"sourceTitle\":\"합방 출연\"");
        assertThat(jsonMapper.readValue(jsonMapper.writeValueAsString(payout), PayoutCommand.class)).isEqualTo(payout);
        assertThat(jsonMapper.readValue(jsonMapper.writeValueAsString(penalty), PenaltyCommand.class))
                .isEqualTo(penalty);
    }
}
