package com.hapbang.auxiliary.notification.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * N15 격리 시험 fixture 내보내기(NT-LOAD-L3·L4·L5). 환경 변수 {@code HAPBANG_N15_OUT}이 있을 때만 돈다. 레포 밖 폴더에 쓴다.
 * <ul>
 *     <li>{@code HAPBANG_N15_WHAT=l4}: {@code <out>/l4/}에 B0와 같은 형식(토픽별 {@code .normal.txt}·{@code .failure.txt},
 *     {@code manifest.json}, {@code content.sha256}, {@code alert-order.json}).</li>
 *     <li>{@code HAPBANG_N15_WHAT=l5}: {@code HAPBANG_L5_SCENARIO}(S·B·M·F), {@code HAPBANG_L5_SEND_AT}(KST
 *     {@code yyyy-MM-ddTHH:mm:ss}), {@code HAPBANG_L5_EXPIRED_DEADLINE}(KST, F만)로 {@code <out>/l5-<시나리오>/}에
 *     {@code members.txt}·{@code main.txt}·{@code expired.txt}·{@code failing-receivers.txt}·{@code manifest.json}.</li>
 *     <li>{@code HAPBANG_N15_WHAT=l3}: {@code HAPBANG_L3_RECEIVERS}(기본 100)·{@code HAPBANG_L3_ALERTS}(기본 6000)로
 *     {@code <out>/l3/}에 {@code members.txt}·{@code alerts.txt}·{@code receivers.txt}.</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "HAPBANG_N15_OUT", matches = ".+")
class N15FixtureExportTest {

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    @Test
    void 정한_시험의_투입_파일과_기대값을_쓴다() throws Exception {
        Path out = Path.of(System.getenv("HAPBANG_N15_OUT").replaceFirst("^~", System.getProperty("user.home")));
        String what = System.getenv().getOrDefault("HAPBANG_N15_WHAT", "");
        switch (what) {
            case "l4" -> l4(out.resolve("l4"));
            case "l5" -> l5(out);
            case "l3" -> l3(out.resolve("l3"));
            default -> throw new IllegalArgumentException("HAPBANG_N15_WHAT은 l4·l5·l3 중 하나다: " + what);
        }
    }

    private static void l4(Path dir) throws IOException {
        B0Fixture fixture = L4Fixtures.generate();
        Files.createDirectories(dir);
        for (String topic : B0Topics.ALL) {
            write(dir.resolve(topic + ".txt"),
                    fixture.allRecords().stream().filter(r -> r.topic().equals(topic)).toList());
            for (boolean failure : new boolean[] {false, true}) {
                write(dir.resolve(topic + (failure ? ".failure.txt" : ".normal.txt")), fixture.alerts().stream()
                        .filter(r -> r.topic().equals(topic) && r.category().failurePhase() == failure).toList());
            }
        }
        Files.writeString(dir.resolve("manifest.json"), JSON.writeValueAsString(fixture.manifest()),
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("content.sha256"), fixture.contentHash() + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("alert-order.json"),
                JSON.writeValueAsString(fixture.alerts().stream().map(B0Record::digest).toList()),
                StandardCharsets.UTF_8);
        assertThat(dir.resolve("manifest.json")).exists();
    }

    private static void l5(Path out) throws IOException {
        L5Fixtures.Scenario scenario = L5Fixtures.Scenario.valueOf(System.getenv("HAPBANG_L5_SCENARIO"));
        LocalDateTime sendAt = LocalDateTime.parse(System.getenv("HAPBANG_L5_SEND_AT"));
        String expiredText = System.getenv("HAPBANG_L5_EXPIRED_DEADLINE");
        LocalDateTime expiredDeadline = expiredText == null ? sendAt.minusMinutes(30) : LocalDateTime.parse(expiredText);
        L5Fixtures.L5Fixture fixture = L5Fixtures.generate(scenario, sendAt, expiredDeadline);
        Path dir = out.resolve("l5-" + scenario.name());
        Files.createDirectories(dir);
        write(dir.resolve("members.txt"), fixture.members());
        write(dir.resolve("main.txt"), fixture.main());
        write(dir.resolve("expired.txt"), fixture.expired());
        Files.writeString(dir.resolve("failing-receivers.txt"), fixture.failingReceivers().stream()
                .map(String::valueOf).collect(Collectors.joining("\n", "", fixture.failingReceivers().isEmpty() ? "" : "\n")),
                StandardCharsets.UTF_8);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("scenario", scenario.name());
        manifest.put("sendAtKst", sendAt.toString());
        manifest.put("expiredDeadlineKst", scenario == L5Fixtures.Scenario.F ? expiredDeadline.toString() : null);
        manifest.put("members", fixture.members().size());
        manifest.put("normal", fixture.normal().size());
        manifest.put("failing", fixture.failing().size());
        manifest.put("expired", fixture.expired().size());
        manifest.put("normalTargets", targets(fixture.normal()));
        manifest.put("failingTargets", targets(fixture.failing()));
        manifest.put("expiredTargets", targets(fixture.expired()));
        manifest.put("failingReceivers", fixture.failingReceivers());
        Files.writeString(dir.resolve("manifest.json"), JSON.writeValueAsString(manifest), StandardCharsets.UTF_8);
        assertThat(dir.resolve("manifest.json")).exists();
    }

    private static void l3(Path dir) throws IOException {
        int receivers = Integer.parseInt(System.getenv().getOrDefault("HAPBANG_L3_RECEIVERS", "100"));
        int alerts = Integer.parseInt(System.getenv().getOrDefault("HAPBANG_L3_ALERTS", "6000"));
        L3Fixtures.L3Fixture fixture = L3Fixtures.generate(receivers, alerts);
        Files.createDirectories(dir);
        write(dir.resolve("members.txt"), fixture.members());
        write(dir.resolve("alerts.txt"), fixture.alerts());
        Files.writeString(dir.resolve("receivers.txt"), fixture.receivers().stream().map(String::valueOf)
                .collect(Collectors.joining("\n", "", "\n")), StandardCharsets.UTF_8);
        assertThat(dir.resolve("alerts.txt")).exists();
    }

    /** 요청서 ID(예약의 대상 ID) */
    private static List<Long> targets(List<B0Record> records) {
        return records.stream().map(r -> Long.parseLong(r.key().substring("appearance-request:".length()))).toList();
    }

    private static void write(Path file, List<B0Record> records) throws IOException {
        Files.writeString(file, records.stream().map(r -> r.line() + "\n").collect(Collectors.joining()),
                StandardCharsets.UTF_8);
    }
}
