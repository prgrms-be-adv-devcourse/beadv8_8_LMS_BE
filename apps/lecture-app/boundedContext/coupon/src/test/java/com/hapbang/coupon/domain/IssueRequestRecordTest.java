package com.hapbang.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

// requests 값 형식: 회원 ID|접수 시각|ACCEPTED|만료 시각|e접수 epoch 뒤에 표식(PUBLISHED·COMPENSATED). 접수 epoch가 없는 옛 형식도 읽는다.
// Lua도 같은 형식을 읽고 쓴다
class IssueRequestRecordTest {

    private static final Instant ACCEPTED_AT = Instant.ofEpochMilli(1_700_000_000_123L);
    private static final Instant EXPIRES_AT = Instant.ofEpochMilli(1_702_592_000_123L);
    private static final String BASE = "7|1700000000123|ACCEPTED|1702592000123";
    private static final String WITH_EPOCH = BASE + "|e3";

    @Test
    void 표식이_없는_접수_기록을_읽고_같은_값으로_쓴다() {
        IssueRequestRecord record = IssueRequestRecord.parse(BASE);

        assertThat(record).isEqualTo(IssueRequestRecord.accepted(7, ACCEPTED_AT, EXPIRES_AT));
        assertThat(record.format()).isEqualTo(BASE);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "|PUBLISHED, true, false",
            "|COMPENSATED, false, true",
            "|PUBLISHED|COMPENSATED, true, true",
            "|COMPENSATED|PUBLISHED, true, true"
    })
    void 표식은_순서와_관계없이_읽는다(String marks, boolean published, boolean compensated) {
        IssueRequestRecord record = IssueRequestRecord.parse(BASE + marks);

        assertThat(record.published()).isEqualTo(published);
        assertThat(record.compensated()).isEqualTo(compensated);
        assertThat(record.memberId()).isEqualTo(7);
        assertThat(record.acceptedAt()).isEqualTo(ACCEPTED_AT);
        assertThat(record.expiresAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    void 접수_epoch가_있는_새_형식을_읽고_같은_값으로_쓴다() {
        IssueRequestRecord record = IssueRequestRecord.parse(WITH_EPOCH);

        assertThat(record).isEqualTo(IssueRequestRecord.accepted(7, ACCEPTED_AT, EXPIRES_AT, 3));
        assertThat(record.epoch()).isEqualTo(3L);
        assertThat(record.format()).isEqualTo(WITH_EPOCH);
    }

    @Test
    void 옛_형식은_접수_epoch가_없고_쓸_때도_epoch_필드를_만들지_않는다() {
        IssueRequestRecord record = IssueRequestRecord.parse(BASE + "|PUBLISHED");

        assertThat(record.epoch()).isNull();
        assertThat(record.format()).isEqualTo(BASE + "|PUBLISHED");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "'', false, false",
            "|PUBLISHED, true, false",
            "|COMPENSATED, false, true",
            "|PUBLISHED|COMPENSATED, true, true",
            "|COMPENSATED|PUBLISHED, true, true"
    })
    void 새_형식도_표식은_순서와_관계없이_읽는다(String marks, boolean published, boolean compensated) {
        IssueRequestRecord record = IssueRequestRecord.parse(WITH_EPOCH + marks);

        assertThat(record.epoch()).isEqualTo(3L);
        assertThat(record.published()).isEqualTo(published);
        assertThat(record.compensated()).isEqualTo(compensated);
    }

    @Test
    void 이벤트에_실을_epoch는_접수_epoch이고_없으면_대신할_epoch다() {
        assertThat(IssueRequestRecord.parse(WITH_EPOCH).epochOr(9)).isEqualTo(3);
        assertThat(IssueRequestRecord.parse(BASE).epochOr(9)).isEqualTo(9);
    }

    @Test
    void 쓸_때는_PUBLISHED_COMPENSATED_순서다() {
        assertThat(new IssueRequestRecord(7, ACCEPTED_AT, EXPIRES_AT, true, true).format())
                .isEqualTo(BASE + "|PUBLISHED|COMPENSATED");
        assertThat(IssueRequestRecord.parse(BASE + "|COMPENSATED|PUBLISHED").format())
                .isEqualTo(BASE + "|PUBLISHED|COMPENSATED");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "7|1700000000123|ACCEPTED",
            "7|1700000000123|ISSUED|1702592000123",
            "x|1700000000123|ACCEPTED|1702592000123",
            "-7|1700000000123|ACCEPTED|1702592000123",
            "7||ACCEPTED|1702592000123",
            "7|1700000000123|ACCEPTED|1702592000123|",
            "7|1700000000123|ACCEPTED|1702592000123|DONE",
            "7|1700000000123|ACCEPTED|1702592000123|PUBLISHED|PUBLISHED",
            "7|1700000000123|ACCEPTED|1702592000123|COMPENSATED|COMPENSATED",
            "99999999999999999999|1700000000123|ACCEPTED|1702592000123",
            "7|1700000000123|ACCEPTED|1702592000123|e",
            "7|1700000000123|ACCEPTED|1702592000123|ex",
            "7|1700000000123|ACCEPTED|1702592000123|e-1",
            "7|1700000000123|ACCEPTED|1702592000123|e3|e3",
            "7|1700000000123|ACCEPTED|1702592000123|PUBLISHED|e3",
            "7|1700000000123|ACCEPTED|1702592000123|e99999999999999999999"
    })
    void 형식이_틀리면_거절한다(String value) {
        assertThatThrownBy(() -> IssueRequestRecord.parse(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 값이_없으면_거절한다() {
        assertThatThrownBy(() -> IssueRequestRecord.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
