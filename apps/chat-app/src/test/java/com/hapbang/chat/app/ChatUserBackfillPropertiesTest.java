package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 백필 설정의 기본값은 코드에 있고(application.yaml에 두지 않는다), 같은 키의 yaml·환경 변수가 덮는다 */
class ChatUserBackfillPropertiesTest {

    private static final String PREFIX = "hapbang.chat.backfill";

    @Test
    void 아무것도_설정하지_않으면_코드_기본값을_쓴다() {
        ChatUserBackfillProperties properties = bind(Map.of());

        assertThat(properties.pageSize()).isEqualTo(500);
        assertThat(properties.maxLimit()).isEqualTo(5_000);
        assertThat(properties.conflictMaxAttempts()).isEqualTo(3);
    }

    @Test
    void 설정한_값이_코드_기본값을_덮는다() {
        ChatUserBackfillProperties properties = bind(Map.of(PREFIX + ".page-size", "50", PREFIX + ".max-limit", "200"));

        assertThat(properties.pageSize()).isEqualTo(50);
        assertThat(properties.maxLimit()).isEqualTo(200);
        assertThat(properties.conflictMaxAttempts()).isEqualTo(3);
    }

    private static ChatUserBackfillProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate(PREFIX,
                ChatUserBackfillProperties.class);
    }
}
