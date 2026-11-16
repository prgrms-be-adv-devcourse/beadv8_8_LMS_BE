package com.hapbang.auxiliary.notification.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * B0 고정 이벤트 불변(N15, ND-189). N15가 생성기에 크기 매개변수를 더해도 B0 기본 출력은 공식 B0 5회(2026-11-15)에 쓴 것과 바이트
 * 단위로 같아야 한다. 기대 해시는 그때 내보낸 파일({@code ~/.hapbang/load-tests/b0/fixture}의 {@code content.sha256},
 * {@code manifest.json}, {@code alert-order.json})에서 옮겼다. 이 시험을 고쳐 통과시키지 않는다.
 */
class B0FixtureInvarianceTest {

    private static final String CONTENT_SHA256 = "bea6c1072fe50342c1d55f5961d1c7afe9d2df2a826164933c81ea5f4871341a";
    private static final String MANIFEST_SHA256 = "23c8476750c0b5a38eb939b47c2bf6f735d37702679f23b7031b3a1c336e0dec";
    private static final String ALERT_ORDER_SHA256 = "f4d24f3e4bd42109a7cb79cca5f85a54a98488ac8602e1f90a40679d2ab3ecc1";

    /** 내보내기({@link B0FixtureExportTest})와 같은 직렬화 */
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private static final B0Fixture FIXTURE = B0FixtureGenerator.generate(B0FixtureGenerator.DEFAULT_SEED);

    @Test
    void B0_기본_출력의_레코드_내용이_공식_B0와_같다() {
        assertThat(FIXTURE.contentHash()).isEqualTo(CONTENT_SHA256);
    }

    @Test
    void B0_기본_출력의_manifest가_공식_B0와_같다() {
        assertThat(B0Record.sha256(JSON.writeValueAsString(FIXTURE.manifest()))).isEqualTo(MANIFEST_SHA256);
    }

    @Test
    void B0_기본_출력의_알림_레코드_순서가_공식_B0와_같다() {
        String order = JSON.writeValueAsString(FIXTURE.alerts().stream().map(B0Record::digest).toList());
        assertThat(B0Record.sha256(order)).isEqualTo(ALERT_ORDER_SHA256);
    }
}
