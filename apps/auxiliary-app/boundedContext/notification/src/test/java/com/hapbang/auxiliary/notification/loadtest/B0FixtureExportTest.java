package com.hapbang.auxiliary.notification.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * B0 고정 이벤트 파일 내보내기(N13). 환경 변수 {@code HAPBANG_B0_OUT}이 있을 때만 돈다(평소 시험에서는 건너뜀). seed는
 * {@code HAPBANG_B0_SEED}(기본 20261114). 레포 밖 폴더에 쓴다: 토픽별 {@code <토픽>.txt}(console producer 한 줄 형식, 파일 순서),
 * {@code manifest.json}. 실행: {@code HAPBANG_B0_OUT=~/.hapbang/load-tests/b0/fixture ./gradlew :notification:test --tests
 * '*B0FixtureExportTest'}
 */
@EnabledIfEnvironmentVariable(named = "HAPBANG_B0_OUT", matches = ".+")
class B0FixtureExportTest {

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    @Test
    void 토픽별_투입_파일과_manifest를_쓴다() throws Exception {
        Path out = Path.of(System.getenv("HAPBANG_B0_OUT").replaceFirst("^~", System.getProperty("user.home")));
        String seedText = System.getenv("HAPBANG_B0_SEED");
        long seed = seedText == null ? B0FixtureGenerator.DEFAULT_SEED : Long.parseLong(seedText);
        B0Fixture fixture = B0FixtureGenerator.generate(seed);
        Files.createDirectories(out);

        for (String topic : B0Topics.ALL) {
            List<B0Record> records = fixture.allRecords().stream().filter(r -> r.topic().equals(topic)).toList();
            Files.writeString(out.resolve(topic + ".txt"),
                    records.stream().map(B0Record::line).collect(Collectors.joining("\n", "", "\n")),
                    StandardCharsets.UTF_8);
        }
        // 두 단계 투입(ND-166): 알림 토픽마다 정상 단계·실패 단계 파일. 두 파일을 이으면 위 토픽 파일과 같다
        for (String topic : B0Topics.ALL) {
            for (boolean failure : new boolean[] {false, true}) {
                List<B0Record> records = fixture.alerts().stream()
                        .filter(r -> r.topic().equals(topic) && r.category().failurePhase() == failure).toList();
                Files.writeString(out.resolve(topic + (failure ? ".failure.txt" : ".normal.txt")),
                        records.stream().map(r -> r.line() + "\n").collect(Collectors.joining()),
                        StandardCharsets.UTF_8);
            }
        }
        Files.writeString(out.resolve("manifest.json"), JSON.writeValueAsString(fixture.manifest()),
                StandardCharsets.UTF_8);
        Files.writeString(out.resolve("content.sha256"), fixture.contentHash() + "\n", StandardCharsets.UTF_8);
        // 순서 쌍의 index(알림 레코드 위치)를 digest로 바꿀 때 쓴다(verify_b0.py V0-3)
        Files.writeString(out.resolve("alert-order.json"),
                JSON.writeValueAsString(fixture.alerts().stream().map(B0Record::digest).toList()),
                StandardCharsets.UTF_8);

        assertThat(out.resolve("manifest.json")).exists();
    }
}
