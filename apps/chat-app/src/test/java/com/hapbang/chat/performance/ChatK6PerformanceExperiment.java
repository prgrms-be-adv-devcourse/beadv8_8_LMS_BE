package com.hapbang.chat.performance;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;

import com.hapbang.chat.app.ChatGroupRoomFacade;
import com.hapbang.chat.app.ChatGroupTestConfig;
import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.InMemoryChatRecruitSource;
import com.hapbang.chat.domain.ChatGroupInviteResult;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.shared.member.domain.Role;

/** 실제 앱/격리 DB·Redis를 준비하고 별도 k6 프로세스를 실행한다. 방 정원은 호스트 포함 200명 이하. */
@ChatE2ETest
@Import(ChatGroupTestConfig.class)
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=20", "logging.level.root=WARN"})
class ChatK6PerformanceExperiment {
    @LocalServerPort int port;
    @Autowired ChatGroupRoomFacade groups;
    @Autowired InMemoryChatRecruitSource recruits;
    @Autowired ChatUserUseCase users;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    @WithMockUser(roles = "INFLUENCER")
    void runK6() throws Exception {
        int people = setting("CHAT_K6_PEOPLE", 100, 2, 200);
        int rate = setting("CHAT_K6_RATE", 10, 1, 100);
        int seconds = setting("CHAT_K6_SECONDS", 30, 1, 300);
        users.createIfAbsent(activeUser(HOST_ID, "k6호스트", Role.INFLUENCER));
        var ids = new ArrayList<Long>();
        for (int i = 0; i < people - 1; i++) {
            long id = 10000L + i;
            users.createIfAbsent(activeUser(id, "k6게스트" + i, Role.INFLUENCER));
            ids.add(id);
        }
        recruits.putRecruit(900002L, HOST_ID, "k6 로컬 실험", "RECRUITING");
        recruits.addApplicants(900002L, ids.toArray(Long[]::new));
        long room = groups.createRoom(HOST_ID, 900002L).room().chatRoomId();
        for (int i = 0; i < ids.size(); i += 50) {
            assertThat(groups.invite(room, HOST_ID, UUID.randomUUID(), ids.subList(i, Math.min(i + 50, ids.size()))).results())
                    .allSatisfy(r -> assertThat(r.result()).isEqualTo(ChatGroupInviteResult.JOINED));
        }
        long sequence = jdbc.queryForObject("select last_sequence from chats.chat_room where id=?", Long.class, room);
        Path directory = Path.of("build/reports/chat-k6").toAbsolutePath();
        Files.createDirectories(directory);
        String name = people + "-" + rate + "-" + seconds;
        String prefix = "k6-" + UUID.randomUUID() + ":";
        var builder = new ProcessBuilder("k6", "run", "--quiet", "--no-color", "docs/experiments/scripts/group-chat.js");
        var env = builder.environment();
        env.put("CHAT_K6_PEOPLE", String.valueOf(people));
        env.put("CHAT_K6_RATE", String.valueOf(rate));
        env.put("CHAT_K6_SECONDS", String.valueOf(seconds));
        env.put("CHAT_K6_URL", "http://localhost:" + port);
        env.put("CHAT_K6_ROOM", String.valueOf(room));
        env.put("CHAT_K6_SEQUENCE", String.valueOf(sequence));
        env.put("CHAT_K6_START", String.valueOf(System.currentTimeMillis() + 10000));
        env.put("CHAT_K6_PREFIX", prefix);
        env.put("CHAT_K6_SUMMARY", directory.resolve(name + ".json").toString());
        builder.redirectErrorStream(true).redirectOutput(directory.resolve(name + ".log").toFile());
        Process process = builder.start();
        try {
            assertThat(process.waitFor(seconds + 60, TimeUnit.SECONDS)).as("k6 종료 제한 시간").isTrue();
            long stored = jdbc.queryForObject("select count(*) from chats.chat_message where chat_room_id=? and content like ?",
                    Long.class, room, prefix + "%");
            Files.writeString(directory.resolve(name + "-db.json"), "{\"members\":" + people + ",\"expectedMessages\":"
                    + (rate * seconds) + ",\"storedMessages\":" + stored + ",\"k6ExitCode\":" + process.exitValue() + "}");
            assertThat(process.exitValue()).as("k6 로그: " + directory.resolve(name + ".log")).isZero();
            assertThat(stored).isEqualTo(rate * seconds);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static int setting(String name, int fallback, int min, int max) {
        int value = Integer.parseInt(System.getenv().getOrDefault(name, String.valueOf(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException(name + " 범위 " + min + ".." + max);
        return value;
    }
}
