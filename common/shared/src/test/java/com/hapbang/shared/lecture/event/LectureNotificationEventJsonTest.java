package com.hapbang.shared.lecture.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hapbang.shared.lecture.domain.LectureConfirmationType;
import com.hapbang.shared.lecture.dto.LectureOrderItemDto;

import tools.jackson.databind.json.JsonMapper;

/**
 * 알림용 강의 이벤트 7종(#872, D85)의 JSON 계약. 필드 이름은 알림 계약(event-contracts.md 2장)에 적은 이름이고,
 * 사건 시각은 발행 기록(outbox)에서 읽어 다시 보내도 {@code +09:00}으로 나간다.
 */
class LectureNotificationEventJsonTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final OffsetDateTime AT = OffsetDateTime.of(2026, 11, 11, 10, 30, 0, 0, KST);
    private static final String AT_JSON = "\"2026-11-11T10:30:00+09:00\"";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void 일곱_종_모두_계약한_필드_이름으로_쓰고_다시_읽어도_같다() {
        List<LectureOrderItemDto> items = List.of(new LectureOrderItemDto(10L, 100L, "OBS 방송 세팅", 9_000L),
                new LectureOrderItemDto(11L, 101L, "마이크 고르기", 0L));
        LectureApprovedEvent approved = new LectureApprovedEvent(100L, 1L, "OBS 방송 세팅", 2, AT);
        LectureRejectedEvent rejected = new LectureRejectedEvent(100L, 1L, "OBS 방송 세팅", "음질이 낮습니다.", 1, AT);
        LectureOrderPaidEvent paid = new LectureOrderPaidEvent(7L, 2L, items, AT);
        LectureOrderHoldFailedEvent failed = new LectureOrderHoldFailedEvent(7L, 2L, items, AT);
        LectureOrderItemCanceledEvent canceled = new LectureOrderItemCanceledEvent(10L, 2L, "OBS 방송 세팅", 9_000L, AT);
        LectureOrderItemConfirmedEvent confirmed = new LectureOrderItemConfirmedEvent(10L, 2L, "OBS 방송 세팅",
                LectureConfirmationType.AUTO, AT);
        LectureObjectionFiledEvent filed = new LectureObjectionFiledEvent(500L, 10L, 2L, 1L, "OBS 방송 세팅", AT);

        assertThat(json(approved)).contains("\"lectureId\":100", "\"sellerUserId\":1",
                "\"lectureTitle\":\"OBS 방송 세팅\"", "\"inspectionSequence\":2", "\"approvedAt\":" + AT_JSON);
        assertThat(json(rejected)).contains("\"rejectionReason\":\"음질이 낮습니다.\"", "\"inspectionSequence\":1",
                "\"rejectedAt\":" + AT_JSON);
        assertThat(json(paid)).contains("\"orderId\":7", "\"buyerUserId\":2",
                "\"items\":[{\"orderItemId\":10,\"lectureId\":100,\"lectureTitle\":\"OBS 방송 세팅\","
                        + "\"buyerAmountHp\":9000},{\"orderItemId\":11", "\"paidAt\":" + AT_JSON);
        assertThat(json(failed)).contains("\"items\":[{\"orderItemId\":10", "\"failedAt\":" + AT_JSON);
        assertThat(json(canceled)).contains("\"orderItemId\":10", "\"buyerAmountHp\":9000",
                "\"canceledAt\":" + AT_JSON);
        assertThat(json(confirmed)).contains("\"confirmationType\":\"AUTO\"", "\"confirmedAt\":" + AT_JSON);
        assertThat(json(filed)).contains("\"inquiryId\":500", "\"orderItemId\":10", "\"buyerUserId\":2",
                "\"sellerUserId\":1", "\"filedAt\":" + AT_JSON);
        for (Object event : List.of(approved, rejected, paid, failed, canceled, confirmed, filed)) {
            assertThat(roundTrip(event)).isEqualTo(event);
        }
    }

    @Test
    void 다른_offset으로_만들어도_같은_시각의_KST_offset으로_맞춘다() {
        OffsetDateTime utc = AT.withOffsetSameInstant(ZoneOffset.UTC);

        LectureOrderPaidEvent paid = new LectureOrderPaidEvent(7L, 2L, List.of(), utc);
        LectureOrderItemConfirmedEvent confirmed = new LectureOrderItemConfirmedEvent(10L, 2L, "OBS 방송 세팅",
                LectureConfirmationType.DIRECT, utc);

        assertThat(paid.paidAt()).isEqualTo(AT);
        assertThat(paid.paidAt().getOffset()).isEqualTo(KST);
        assertThat(confirmed.confirmedAt().getOffset()).isEqualTo(KST);
    }

    private String json(Object event) {
        return jsonMapper.writeValueAsString(event);
    }

    private Object roundTrip(Object event) {
        return jsonMapper.readValue(json(event), event.getClass());
    }
}
