package com.hapbang.auxiliary.notification.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * B0 고정 이벤트 구조 불변(N15, ND-189·ND-201). N15가 생성기에 크기 매개변수를 더해도 B0 기본 출력의 구조(레코드 순서·토픽·key·
 * 타입·분류·파티션, 이벤트 본문과 무관한 manifest 값)는 공식 B0 5회(2026-11-15)와 같아야 한다.
 * <p>
 * 이벤트 본문(JSON)은 shared 이벤트 직렬화에 묶여 있어 고정하지 않는다. shared에 필드를 더한 다른 팀 PR의 CI를 막지 않기 위해서다.
 * 구조 해시는 바이트 단위 내용 해시(공식 B0 파일의 {@code content.sha256} {@code bea6c107…}, {@code manifest.json}
 * {@code 23c84767…}, {@code alert-order.json} {@code f4d24f3e…})가 같음을 확인한 코드(NR-102)에서 옮겼다. 이 시험을 고쳐 통과시키지 않는다.
 */
class B0FixtureInvarianceTest {

    private static final String STRUCTURE_SHA256 = "c7adc56fcddcda9a68b29f782d91e751fbb1c6d1f5ca5930f85f5e5c7e44867e";
    private static final String MANIFEST_STRUCTURE_SHA256 = "278e6eb4b97c8a3ba2450d9390e1f7b0c11d4baf8464f2b9449118a600071a15";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final B0Fixture FIXTURE = B0FixtureGenerator.generate(B0FixtureGenerator.DEFAULT_SEED);

    /** 레코드마다 토픽·key·타입·분류·파티션을 파일 순서(회원 → 알림)대로. 본문(value)은 넣지 않는다 */
    static String structure(B0Fixture fixture) {
        return Stream.concat(fixture.members().stream(), fixture.alerts().stream())
                .map(r -> r.topic() + '\t' + r.key() + '\t' + r.typeId() + '\t' + r.category() + '\t' + r.partition())
                .collect(Collectors.joining("\n"));
    }

    /** manifest에서 본문 digest를 담은 파티션별 순서(partitionSequences)만 뺀 값 */
    static String manifestStructure(B0Fixture fixture) {
        ObjectNode node = JSON.valueToTree(fixture.manifest());
        node.remove("partitionSequences");
        return JSON.writeValueAsString(node);
    }

    @Test
    void B0_기본_출력의_레코드_구조가_공식_B0와_같다() {
        assertThat(B0Record.sha256(structure(FIXTURE))).isEqualTo(STRUCTURE_SHA256);
    }

    @Test
    void B0_기본_출력의_manifest가_본문_digest를_빼고_공식_B0와_같다() {
        assertThat(B0Record.sha256(manifestStructure(FIXTURE))).isEqualTo(MANIFEST_STRUCTURE_SHA256);
    }
}
