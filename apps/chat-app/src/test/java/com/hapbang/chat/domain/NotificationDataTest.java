package com.hapbang.chat.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 저장한 알림 값 JSON을 알림 종류의 record로 읽는지, 읽을 수 없는 값이 이력 조회를 막지 않는지 검증한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class NotificationDataTest {

    @Test
    void 저장한_JSON을_종류의_record로_읽는다() {
        AppearanceRequestSentData requestSent = new AppearanceRequestSentData(1, "호스트", "금요일 밤 합방", 50_000L,
                "2026-11-20T21:00:00", "2026-11-13T23:59:00");

        // jsonb는 키 순서를 바꿔 돌려주므로 순서와 관계없이 읽는다
        assertThat(NotificationData.read("""
                {"v":1,"fee":50000,"title":"금요일 밤 합방","scheduledAt":"2026-11-20T21:00:00",
                 "hostNickname":"호스트","acceptDeadline":"2026-11-13T23:59:00"}""", AppearanceRequestSentData.class))
                .isEqualTo(requestSent);
        assertThat(NotificationData.read(NotificationData.write(requestSent), AppearanceRequestSentData.class))
                .isEqualTo(requestSent);
        assertThat(NotificationData.read("{\"v\":1,\"guestNickname\":\"게스트\",\"recruitTitle\":\"주말 게임 합방 모집\"}",
                AppearanceApplicationSubmittedData.class))
                .isEqualTo(new AppearanceApplicationSubmittedData(1, "게스트", "주말 게임 합방 모집"));
    }

    @Test
    void 쓰는_JSON은_record_순서의_키와_숫자_타입_그대로다() {
        assertThat(NotificationData.write(new AppearanceRequestSentData(1, "호스트", "금요일 밤 합방", 50_000L,
                "2026-11-20T21:00:00", "2026-11-13T23:59:00"))).isEqualTo("""
                {"v":1,"hostNickname":"호스트","title":"금요일 밤 합방","fee":50000,\
                "scheduledAt":"2026-11-20T21:00:00","acceptDeadline":"2026-11-13T23:59:00"}""");
        assertThat(NotificationData.write(new AppearanceApplicationSubmittedData(1, "게스트", "주말 게임 합방 모집")))
                .isEqualTo("{\"v\":1,\"guestNickname\":\"게스트\",\"recruitTitle\":\"주말 게임 합방 모집\"}");
    }

    /** 타입이 다르거나 빠진 값을 v1 값처럼 바꿔 읽지 않는다. 프론트가 대체 표시를 쓸 수 있도록 null로 둔다 */
    @ParameterizedTest
    @ValueSource(strings = {
            // 버전이 소수: 정수로 잘라 읽지 않는다
            "{\"v\":1.9,\"hostNickname\":\"호스트\",\"title\":\"t\",\"fee\":1,\"scheduledAt\":\"s\",\"acceptDeadline\":\"a\"}",
            // 금액이 문자열: 숫자로 바꿔 읽지 않는다
            "{\"v\":1,\"hostNickname\":\"호스트\",\"title\":\"t\",\"fee\":\"50000\",\"scheduledAt\":\"s\",\"acceptDeadline\":\"a\"}",
            // 닉네임이 숫자: 문자열로 바꿔 읽지 않는다
            "{\"v\":1,\"hostNickname\":123,\"title\":\"t\",\"fee\":1,\"scheduledAt\":\"s\",\"acceptDeadline\":\"a\"}",
            // 일시가 숫자·불리언: 문자열로 바꿔 읽지 않는다
            "{\"v\":1,\"hostNickname\":\"호스트\",\"title\":\"t\",\"fee\":1,\"scheduledAt\":1.5,\"acceptDeadline\":true}",
            // 기본 타입(v)이 없음: 0으로 채워 읽지 않는다
            "{\"hostNickname\":\"호스트\",\"title\":\"t\",\"fee\":1,\"scheduledAt\":\"s\",\"acceptDeadline\":\"a\"}",
            // 기본 타입(fee)이 null
            "{\"v\":1,\"hostNickname\":\"호스트\",\"title\":\"t\",\"fee\":null,\"scheduledAt\":\"s\",\"acceptDeadline\":\"a\"}",
            // 필요한 키(title)가 없음
            "{\"v\":1,\"hostNickname\":\"호스트\",\"fee\":1,\"scheduledAt\":\"s\",\"acceptDeadline\":\"a\"}",
            // 필요한 키(hostNickname)가 null
            "{\"v\":1,\"hostNickname\":null,\"title\":\"t\",\"fee\":1,\"scheduledAt\":\"s\",\"acceptDeadline\":\"a\"}"
    })
    void 타입이_다르거나_빠진_값은_바꿔_읽지_않고_null이다(String json, CapturedOutput output) {
        assertThat(NotificationData.read(json, AppearanceRequestSentData.class)).isNull();
        assertThat(output).contains("알림 값을 읽을 수 없어 비워 둠 type=AppearanceRequestSentData");
    }

    @Test
    void 지원_알림도_타입이_다르면_null이다() {
        assertThat(NotificationData.read("{\"v\":1,\"guestNickname\":false,\"recruitTitle\":\"모집\"}",
                AppearanceApplicationSubmittedData.class)).isNull();
        assertThat(NotificationData.read("{\"v\":\"1\",\"guestNickname\":\"게스트\",\"recruitTitle\":\"모집\"}",
                AppearanceApplicationSubmittedData.class)).isNull();
    }

    @Test
    void 같은_버전에서_더한_키는_무시하고_읽는다() {
        assertThat(NotificationData.read("{\"v\":1,\"guestNickname\":\"게스트\",\"recruitTitle\":\"모집\",\"added\":true}",
                AppearanceApplicationSubmittedData.class))
                .isEqualTo(new AppearanceApplicationSubmittedData(1, "게스트", "모집"));
    }

    @Test
    void 값이_없으면_null이다() {
        assertThat(NotificationData.read(null, AppearanceRequestSentData.class)).isNull();
    }

    @Test
    void 읽을_수_없는_값은_예외_없이_null이다() {
        assertThat(NotificationData.read("{not-json", AppearanceRequestSentData.class)).isNull();
        assertThat(NotificationData.read("[1,2]", AppearanceRequestSentData.class)).isNull();
        assertThat(NotificationData.read("{\"fee\":\"많이\"}", AppearanceRequestSentData.class)).isNull();
    }
}
