package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import com.hapbang.chat.app.dto.ChatUserBackfillResult;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.app.exception.ChatUserBackfillException;
import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.fixture.ChatNativeSql;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;
import com.sun.net.httpserver.HttpServer;

import jakarta.persistence.EntityManager;

/**
 * 기존 회원 백필(설계 문서 5.4)을 실제 PostgreSQL과 실제 회원 내부 API HTTP Interface(chat-app 전용)로 검증한다. 회원 내부 API는 가짜 HTTP 서버다
 * (billing 백필 테스트와 같은 방식). 페이지 크기를 2로 줄여 여러 페이지·중간 실패·재개를 본다.
 * 가짜 서버 주소 때문에 이 클래스만 쓰는 컨텍스트라 끝나면 닫는다.
 */
@IntegrationTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChatUserBackfillIntegrationTest {

    private static final String URL = "/api/v1/chats/admin/chat-users/backfill";
    private static final long ADMIN = 900L;
    private static final int MAX_LIMIT = 10;

    private static final HttpServer SERVER = startServer();
    // 가짜 회원 원본: 회원 ID → 회원 정보. 키셋 페이지로 응답한다
    private static final ConcurrentSkipListMap<Long, UserDto> MEMBERS = new ConcurrentSkipListMap<>();
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    // 응답 직전에 실행할 일(조회 뒤·저장 전 회원 이벤트 반영 재현)과 n번째 요청에 강제할 응답 상태
    private static volatile Runnable beforeRespond;
    private static volatile int failOnRequest;
    private static volatile int forcedStatus;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("hapbang.clients.member.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("hapbang.chat.backfill.page-size", () -> "2");
        registry.add("hapbang.chat.backfill.max-limit", () -> String.valueOf(MAX_LIMIT));
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Autowired
    private ChatUserBackfillService backfillService;

    @Autowired
    private ChatUserSyncService chatUserSyncService;

    @MockitoSpyBean
    private ChatUserRepository chatUserRepository;

    private ChatNativeSql sql;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    void setUpSql(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        sql = ChatNativeSql.of(entityManager, transactionManager);
    }

    @BeforeEach
    void reset() {
        MEMBERS.clear();
        REQUESTS.clear();
        beforeRespond = null;
        failOnRequest = 0;
        forcedStatus = 0;
        clearControlTables();
    }

    @AfterEach
    void clearControlTables() {
        sql.update("delete from chats.chat_publish_checkpoint");
        sql.update("delete from chats.chat_control_outbox");
    }

    @Test
    void 없는_회원은_만들고_오래된_복제본은_갱신하고_같은_버전은_건너뛴다() {
        chatUserSyncService.syncJoined(member(1L, "옛닉네임", Role.INFLUENCER, UserStatus.ACTIVE, 1L));
        chatUserSyncService.syncJoined(member(2L, "그대로", Role.INFLUENCER, UserStatus.ACTIVE, 3L));
        source(member(1L, "새닉네임", Role.USER, UserStatus.ACTIVE, 2L));
        source(member(2L, "그대로", Role.INFLUENCER, UserStatus.ACTIVE, 3L));
        source(member(3L, "새회원", Role.USER, UserStatus.ACTIVE, 1L));

        ChatUserBackfillResult result = backfillService.backfill(0, MAX_LIMIT);

        assertThat(result).extracting(ChatUserBackfillResult::scanned, ChatUserBackfillResult::created,
                        ChatUserBackfillResult::updated, ChatUserBackfillResult::skippedStale,
                        ChatUserBackfillResult::failed, ChatUserBackfillResult::lastProcessedId,
                        ChatUserBackfillResult::nextAfterId, ChatUserBackfillResult::reachedEnd,
                        ChatUserBackfillResult::completed)
                .containsExactly(3L, 1L, 1L, 1L, 0L, 3L, null, true, true);
        assertThat(REQUESTS).containsExactly("afterId=0&size=2", "afterId=2&size=2");
        assertThat(chatUserRepository.findById(1L)).hasValueSatisfying(chatUser -> {
            assertThat(chatUser.getNickname()).isEqualTo("새닉네임");
            assertThat(chatUser.getMemberVersion()).isEqualTo(2L);
        });
        assertThat(chatUserRepository.findById(3L)).get().extracting(ChatUser::canChat).isEqualTo(false);
        // 기존 복제본을 갱신해 채팅 불가가 된 회원만 백필 사유로 남긴다. 새로 만든 회원(3)은 남기지 않는다
        assertThat(controlRows()).singleElement().satisfies(row -> {
            assertThat(row.get("user_id")).isEqualTo(1L);
            assertThat(row.get("member_version")).isEqualTo(2L);
            assertThat(row.get("reason")).isEqualTo("MEMBER_BACKFILL");
        });
    }

    @Test
    void 최신_이벤트가_반영된_뒤_도착한_옛_백필_페이지는_되돌리지_않는다() {
        chatUserSyncService.syncJoined(member(1L, "가입", Role.INFLUENCER, UserStatus.ACTIVE, 1L));
        source(member(1L, "옛스냅샷", Role.USER, UserStatus.ACTIVE, 2L));
        // 페이지를 읽은 뒤 저장하기 전에 더 새로운 변경 이벤트가 먼저 반영된다
        beforeRespond = () -> chatUserSyncService.syncModified(
                member(1L, "최신", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 3L));

        ChatUserBackfillResult result = backfillService.backfill(0, MAX_LIMIT);

        assertThat(result.skippedStale()).isEqualTo(1L);
        assertThat(result.completed()).isTrue();
        assertThat(chatUserRepository.findById(1L)).hasValueSatisfying(chatUser -> {
            assertThat(chatUser.getNickname()).isEqualTo("최신");
            assertThat(chatUser.getMemberVersion()).isEqualTo(3L);
            assertThat(chatUser.canChat()).isTrue();
        });
        assertThat(controlRows()).isEmpty();
    }

    @Test
    void 백필과_가입_이벤트가_동시에_만들면_충돌을_감지하고_다시_읽어_한_행으로_수렴한다() {
        source(member(7L, "스냅샷", Role.INFLUENCER, UserStatus.SUSPENDED, 2L));
        Answer<?> real = mockingDetails(chatUserRepository).getMockCreationSettings().getDefaultAnswer();
        AtomicBoolean injected = new AtomicBoolean();
        doAnswer(invocation -> {
            Object read = real.answer(invocation);
            if (Long.valueOf(7L).equals(invocation.getArgument(0)) && injected.compareAndSet(false, true)) {
                // 백필이 없다고 읽은 직후, 가입 이벤트가 다른 트랜잭션에서 먼저 만들고 커밋한다
                CompletableFuture.runAsync(() -> chatUserSyncService.syncJoined(
                        member(7L, "가입", Role.INFLUENCER, UserStatus.ACTIVE, 1L))).get(10, TimeUnit.SECONDS);
            }
            return read;
        }).when(chatUserRepository).findById(any());

        ChatUserBackfillResult result = backfillService.backfill(0, MAX_LIMIT);

        assertThat(injected).isTrue();
        assertThat(result).extracting(ChatUserBackfillResult::created, ChatUserBackfillResult::updated,
                ChatUserBackfillResult::failed, ChatUserBackfillResult::completed)
                .containsExactly(0L, 1L, 0L, true);
        assertThat(sql.single("select count(*) from chats.chat_user where id = 7", Long.class))
                .isEqualTo(1L);
        assertThat(chatUserRepository.findById(7L)).hasValueSatisfying(chatUser -> {
            assertThat(chatUser.getMemberVersion()).isEqualTo(2L);
            assertThat(chatUser.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        });
        // 충돌로 롤백된 첫 시도의 기록은 없고, 다시 시도해 갱신한 결과로 한 번만 남는다
        assertThat(controlRows()).singleElement()
                .satisfies(row -> assertThat(row.get("member_version")).isEqualTo(2L));
    }

    @Test
    void 회원_반영이_실패하면_완료로_표시하지_않고_실패_회원을_남기며_다시_실행하면_완료된다() {
        source(member(1L, "회원1", Role.INFLUENCER, UserStatus.ACTIVE, 1L));
        source(member(2L, " ", Role.INFLUENCER, UserStatus.ACTIVE, 1L));
        source(member(3L, "회원3", Role.INFLUENCER, UserStatus.ACTIVE, 1L));

        ChatUserBackfillResult failed = backfillService.backfill(0, MAX_LIMIT);

        assertThat(failed).extracting(ChatUserBackfillResult::created, ChatUserBackfillResult::failed,
                        ChatUserBackfillResult::failedIds, ChatUserBackfillResult::reachedEnd,
                        ChatUserBackfillResult::completed)
                .containsExactly(2L, 1L, List.of(2L), true, false);
        assertThat(chatUserRepository.findById(2L)).isEmpty();

        source(member(2L, "회원2", Role.INFLUENCER, UserStatus.ACTIVE, 1L));
        ChatUserBackfillResult retried = backfillService.backfill(1, MAX_LIMIT);

        assertThat(retried).extracting(ChatUserBackfillResult::created, ChatUserBackfillResult::skippedStale,
                        ChatUserBackfillResult::failed, ChatUserBackfillResult::completed)
                .containsExactly(1L, 1L, 0L, true);
        assertThat(chatUserRepository.count()).isEqualTo(3L);
    }

    @Test
    void 회원_원본_조회가_중간에_실패하면_멈추고_재개_커서부터_이어서_실행한다() {
        for (long id = 1; id <= 4; id++) {
            source(member(id, "회원" + id, Role.INFLUENCER, UserStatus.ACTIVE, 1L));
        }
        failOnRequest = 2;
        forcedStatus = 503;

        assertThatThrownBy(() -> backfillService.backfill(0, MAX_LIMIT))
                .isInstanceOfSatisfying(ChatUserBackfillException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ChatErrorCode.MEMBER_SOURCE_UNAVAILABLE);
                    assertThat(e.getProgress()).extracting(ChatUserBackfillResult::created,
                                    ChatUserBackfillResult::nextAfterId, ChatUserBackfillResult::completed)
                            .containsExactly(2L, 2L, false);
                    assertThat(e.getProperties()).containsEntry("resumeAfterId", 2L);
                });
        assertThat(chatUserRepository.count()).isEqualTo(2L);

        failOnRequest = 0;
        ChatUserBackfillResult resumed = backfillService.backfill(2, MAX_LIMIT);

        assertThat(resumed).extracting(ChatUserBackfillResult::created, ChatUserBackfillResult::completed)
                .containsExactly(2L, true);
        assertThat(chatUserRepository.count()).isEqualTo(4L);
    }

    @Test
    void 한_번에_처리할_수를_넘기면_재개_커서를_돌려준다() {
        for (long id = 1; id <= 3; id++) {
            source(member(id, "회원" + id, Role.INFLUENCER, UserStatus.ACTIVE, 1L));
        }

        ChatUserBackfillResult first = backfillService.backfill(0, 2);

        assertThat(first).extracting(ChatUserBackfillResult::scanned, ChatUserBackfillResult::nextAfterId,
                        ChatUserBackfillResult::reachedEnd, ChatUserBackfillResult::completed)
                .containsExactly(2L, 2L, false, false);
        assertThat(backfillService.backfill(first.nextAfterId(), 2).completed()).isTrue();
        assertThat(chatUserRepository.count()).isEqualTo(3L);
    }

    // ---------------------------------------------------------------------------------------------
    // 관리자 API
    // ---------------------------------------------------------------------------------------------

    @Test
    void 관리자는_백필_API로_실행하고_집계를_RsData로_받는다() throws Exception {
        source(member(1L, "회원1", Role.INFLUENCER, UserStatus.ACTIVE, 1L));

        mockMvc.perform(post(URL).param("limit", "5").with(csrf()).with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.scanned").value(1))
                .andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.failedIds").isEmpty())
                .andExpect(jsonPath("$.data.reachedEnd").value(true))
                .andExpect(jsonPath("$.data.completed").value(true));
        assertThat(REQUESTS).containsExactly("afterId=0&size=2");
    }

    @Test
    void 관리자가_아니면_백필_API를_쓸_수_없다() throws Exception {
        mockMvc.perform(post(URL).param("limit", "5").with(csrf()).with(TestLogin.as(1L, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(URL).with(csrf()))
                .andExpect(status().isUnauthorized());
        assertThat(REQUESTS).isEmpty();
    }

    @Test
    void 처리할_수_범위를_벗어나면_400이다() throws Exception {
        mockMvc.perform(post(URL).param("limit", String.valueOf(MAX_LIMIT + 1)).with(csrf())
                        .with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post(URL).param("afterId", "-1").param("limit", "5").with(csrf()).with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(REQUESTS).isEmpty();
    }

    @Test
    void 회원_원본_장애는_503과_재개_커서로_응답한다() throws Exception {
        source(member(1L, "회원1", Role.INFLUENCER, UserStatus.ACTIVE, 1L));
        failOnRequest = 1;
        forcedStatus = 500;

        mockMvc.perform(post(URL).param("afterId", "0").param("limit", "5").with(csrf())
                        .with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MEMBER_SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.resumeAfterId").value(0))
                .andExpect(jsonPath("$.scanned").value(0));
    }

    @Test
    void 회원_원본이_요청을_거절하면_REJECTED로_멈추고_아무것도_반영하지_않는다() throws Exception {
        source(member(1L, "회원1", Role.INFLUENCER, UserStatus.ACTIVE, 1L));
        failOnRequest = 1;
        forcedStatus = 401;

        mockMvc.perform(post(URL).param("afterId", "0").param("limit", "5").with(csrf())
                        .with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MEMBER_SOURCE_REJECTED"))
                .andExpect(jsonPath("$.resumeAfterId").value(0))
                .andExpect(jsonPath("$.scanned").value(0));
        assertThat(chatUserRepository.count()).isZero();
    }

    @Test
    void 실행_중에_다시_실행하면_409로_거절하고_실행_중인_백필은_끝까지_진행한다() throws Exception {
        source(member(1L, "회원1", Role.INFLUENCER, UserStatus.ACTIVE, 1L));
        List<Throwable> secondRun = new CopyOnWriteArrayList<>();
        // 첫 실행이 회원 원본 응답을 기다리는 동안 두 번째 실행을 시도한다
        beforeRespond = () -> {
            beforeRespond = null;
            try {
                backfillService.backfill(0, MAX_LIMIT);
            } catch (Throwable e) {
                secondRun.add(e);
            }
        };

        ChatUserBackfillResult first = backfillService.backfill(0, MAX_LIMIT);

        assertThat(secondRun).singleElement().isInstanceOfSatisfying(ChatException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ChatErrorCode.CHAT_USER_BACKFILL_ALREADY_RUNNING));
        assertThat(first).extracting(ChatUserBackfillResult::created, ChatUserBackfillResult::completed)
                .containsExactly(1L, true);
        // 끝난 뒤에는 다시 실행할 수 있다
        assertThat(backfillService.backfill(0, MAX_LIMIT).skippedStale()).isEqualTo(1L);
    }

    // ---------------------------------------------------------------------------------------------

    private static UserDto member(Long id, String nickname, Role role, UserStatus status, Long version) {
        return new UserDto(id, nickname, role, status, version);
    }

    private static void source(UserDto user) {
        MEMBERS.put(user.id(), user);
    }

    private List<Map<String, Object>> controlRows() {
        return sql.rows("select user_id, member_version, reason from chats.chat_control_outbox order by user_id",
                Map.of());
    }

    private static String json(UserDto user) {
        return "{\"id\":" + user.id() + ",\"nickname\":\"" + user.nickname() + "\",\"role\":\"" + user.role().name()
                + "\",\"status\":\"" + user.status().name() + "\",\"version\":" + user.version() + "}";
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/internal/members", exchange -> {
                Map<String, String> query = new HashMap<>();
                String raw = exchange.getRequestURI().getRawQuery();
                for (String pair : raw == null ? new String[0] : raw.split("&")) {
                    String[] kv = pair.split("=", 2);
                    query.put(kv[0], kv.length > 1 ? kv[1] : "");
                }
                REQUESTS.add("afterId=" + query.get("afterId") + "&size=" + query.get("size"));
                Runnable hook = beforeRespond;
                if (hook != null) {
                    hook.run();
                }
                String body = null;
                int status = failOnRequest == REQUESTS.size() ? forcedStatus : 200;
                if (status == 200) {
                    long afterId = query.get("afterId") == null ? 0 : Long.parseLong(query.get("afterId"));
                    int size = Integer.parseInt(query.get("size"));
                    List<UserDto> rest = new ArrayList<>(MEMBERS.tailMap(afterId, false).values());
                    List<UserDto> page = rest.subList(0, Math.min(size, rest.size()));
                    StringBuilder items = new StringBuilder();
                    for (UserDto user : page) {
                        items.append(items.isEmpty() ? "" : ",").append(json(user));
                    }
                    body = "{\"statusCode\":200,\"message\":\"ok\",\"data\":{\"items\":[" + items + "]"
                            + (rest.size() > size ? ",\"nextAfterId\":" + page.getLast().id() : "") + "}}";
                }
                byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
                exchange.close();
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
