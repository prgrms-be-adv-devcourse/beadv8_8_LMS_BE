package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.chat.app.ChatRecruitSource.ChatRecruit;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;

import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomStatus;
import com.hapbang.chat.domain.ChatRoomStatusReason;
import com.hapbang.chat.fixture.ChatNativeSql;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.out.ChatRoomRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

import jakarta.persistence.EntityManager;

/**
 * 호스트가 탈퇴하거나 호스트 자격이 없는 역할이 되면 회원 반영과 같은 트랜잭션에서 그 회원의 현재 단체방을 읽기 전용으로 바꾼다(설계 Q31).
 * 회원 변경 이벤트·백필이 쓰는 {@link ChatUserSyncService}를 그대로 부른다.
 */
@IntegrationTest
@Import(ChatGroupTestConfig.class)
class ChatGroupHostUnavailableTest {

    private static final Long HOST = 21L;
    private static final Long OTHER_HOST = 22L;
    private static final Long GUEST = 23L;
    private static final Long RECRUIT_ID = 80L;
    private static final Long OTHER_RECRUIT_ID = 81L;

    @Autowired
    private ChatUserSyncService chatUserSyncService;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatGroupRoomFacade chatGroupRoomFacade;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private InMemoryChatRecruitSource recruits;

    @Autowired
    private ChatGroupRoomService chatGroupRoomService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ChatNativeSql sql;

    @Autowired
    void setUpSql(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        sql = ChatNativeSql.of(entityManager, transactionManager);
    }

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        chatUserUseCase.createIfAbsent(host(HOST, Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 1L));
        chatUserUseCase.createIfAbsent(host(OTHER_HOST, Role.INFLUENCER, UserStatus.ACTIVE, 1L));
        chatUserUseCase.createIfAbsent(activeUser(GUEST, "게스트", Role.INFLUENCER));
        recruits.reset();
        recruits.putRecruit(RECRUIT_ID, HOST, "주말 합방 모집", "RECRUITING");
        recruits.putRecruit(OTHER_RECRUIT_ID, OTHER_HOST, "다른 모집", "RECRUITING");
        recruits.addApplicants(RECRUIT_ID, GUEST);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("test", null, "ROLE_INFLUENCER"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 호스트가_강등되면_사용_중인_방이_읽기_전용이_되고_안내_값과_방_기록과_전달_작업이_남는다() {
        Long roomId = createRoom(HOST, RECRUIT_ID);
        chatGroupRoomFacade.invite(roomId, HOST, UUID.randomUUID(), List.of(GUEST));

        chatUserSyncService.syncModified(host(HOST, Role.USER, UserStatus.ACTIVE, 2L));

        ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow();
        assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.READ_ONLY);
        assertThat(room.getStatusReason()).isEqualTo(ChatRoomStatusReason.HOST_UNAVAILABLE);
        assertThat(room.getHistoryExpiresAt()).isEqualTo(room.getReadOnlyAt().plusDays(30));
        assertThat(room.getReadOnlyAt()).isCloseTo(java.time.LocalDateTime.now(), within(1, ChronoUnit.MINUTES));
        List<Map<String, Object>> notices = hostUnavailableNotices(roomId);
        assertThat(notices).singleElement().satisfies(notice -> {
            assertThat(notice.get("content")).isEqualTo("");
            assertThat(notice.get("sender_user_id")).isEqualTo(HOST);
            assertThat(notice.get("host_user_id")).isEqualTo(String.valueOf(HOST));
        });
        assertThat(hostUnavailableLogs(roomId)).isEqualTo(1L);
        // 연결된 참여자에게 상태 변경을 알리는 안내의 전달 작업(알림 없이 현재 참여자에게)
        assertThat(sql.single("select count(*) from chats.chat_outbox where message_id = :id", Long.class,
                Map.of("id", notices.getFirst().get("id")))).isEqualTo(1L);
    }

    @Test
    void 호스트가_탈퇴하면_읽기_전용이_된다() {
        Long roomId = createRoom(HOST, RECRUIT_ID);

        chatUserSyncService.syncModified(host(HOST, Role.VERIFIED_INFLUENCER, UserStatus.WITHDRAWN, 2L));

        assertThat(statusOf(roomId)).isEqualTo(ChatRoomStatus.READ_ONLY);
    }

    // 회원 버전 규칙: 같은 반영의 재처리·늦게 온 옛 정보는 반영되지 않는다. 자격을 회복해도 기존 방은 읽기 전용을 유지한다
    @Test
    void 같은_이벤트가_다시_오거나_옛_이벤트가_늦게_와도_한_번만_바꾸고_자격을_회복해도_되돌리지_않는다() {
        Long roomId = createRoom(HOST, RECRUIT_ID);
        chatUserSyncService.syncModified(host(HOST, Role.USER, UserStatus.ACTIVE, 2L));
        ChatRoom changed = chatRoomRepository.findById(roomId).orElseThrow();

        chatUserSyncService.syncModified(host(HOST, Role.USER, UserStatus.ACTIVE, 2L));
        chatUserSyncService.syncModified(host(HOST, Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 1L));
        chatUserSyncService.syncModified(host(HOST, Role.INFLUENCER, UserStatus.ACTIVE, 3L));
        chatUserSyncService.syncModified(host(HOST, Role.USER, UserStatus.ACTIVE, 4L));

        ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow();
        assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.READ_ONLY);
        assertThat(room.getHistoryExpiresAt()).isEqualTo(changed.getHistoryExpiresAt());
        assertThat(room.getStatusVersion()).isEqualTo(changed.getStatusVersion());
        assertThat(hostUnavailableNotices(roomId)).hasSize(1);
        assertThat(hostUnavailableLogs(roomId)).isEqualTo(1L);
    }

    @Test
    void 인증된_인플루언서가_인플루언서가_되거나_정지돼도_방은_그대로다() {
        Long roomId = createRoom(HOST, RECRUIT_ID);

        chatUserSyncService.syncModified(host(HOST, Role.INFLUENCER, UserStatus.ACTIVE, 2L));
        assertThat(statusOf(roomId)).isEqualTo(ChatRoomStatus.ACTIVE);

        // 일시 중지는 회원 정지 기능이 생긴 뒤에 다룬다
        chatUserSyncService.syncModified(host(HOST, Role.INFLUENCER, UserStatus.SUSPENDED, 3L));
        assertThat(statusOf(roomId)).isEqualTo(ChatRoomStatus.ACTIVE);
        assertThat(hostUnavailableNotices(roomId)).isEmpty();
    }

    @Test
    void 이미_폐쇄한_방과_다른_호스트의_방은_건드리지_않고_백필로_반영해도_바꾼다() {
        Long closedId = createRoom(HOST, RECRUIT_ID);
        chatGroupRoomFacade.close(closedId, HOST);
        Long currentId = createRoom(HOST, RECRUIT_ID);
        Long otherId = createRoom(OTHER_HOST, OTHER_RECRUIT_ID);
        ChatRoom closedBefore = chatRoomRepository.findById(closedId).orElseThrow();

        chatUserSyncService.syncBackfill(host(HOST, Role.USER, UserStatus.ACTIVE, 2L));

        ChatRoom closed = chatRoomRepository.findById(closedId).orElseThrow();
        assertThat(closed.getStatusReason()).isEqualTo(ChatRoomStatusReason.CLOSED_BY_HOST);
        assertThat(closed.getHistoryExpiresAt()).isEqualTo(closedBefore.getHistoryExpiresAt());
        assertThat(hostUnavailableNotices(closedId)).isEmpty();
        assertThat(chatRoomRepository.findById(currentId).orElseThrow().getStatusReason())
                .isEqualTo(ChatRoomStatusReason.HOST_UNAVAILABLE);
        assertThat(statusOf(otherId)).isEqualTo(ChatRoomStatus.ACTIVE);
    }

    // 방 생성과 호스트 자격 상실 반영은 호스트의 회원 복제본 행에서 직렬화된다. 생성이 먼저 잠그면 반영이 커밋을 기다려 새 방을 바꾼다
    @Test
    void 방_생성이_커밋하기_전에_호스트가_강등되면_반영이_생성을_기다려_새_방을_읽기_전용으로_바꾼다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch created = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Long> creating = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                Long id = chatGroupRoomService.createRoom(HOST, recruit()).chatRoomId();
                created.countDown();
                awaitQuietly(release);
                return id;
            }));
            assertThat(created.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> demoting = pool.submit(
                    () -> chatUserSyncService.syncModified(host(HOST, Role.USER, UserStatus.ACTIVE, 2L)));
            assertThatThrownBy(() -> demoting.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            release.countDown();
            Long roomId = creating.get(10, TimeUnit.SECONDS);
            demoting.get(10, TimeUnit.SECONDS);

            assertThat(chatRoomRepository.findById(roomId).orElseThrow().getStatusReason())
                    .isEqualTo(ChatRoomStatusReason.HOST_UNAVAILABLE);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // 반영이 먼저 잠그면 생성이 그 커밋을 기다리고, 바뀐 자격을 보고 거절한다
    @Test
    void 호스트_강등이_커밋하기_전에_방을_만들면_생성이_반영을_기다려_거절된다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch demoted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> demoting = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> {
                        chatUserSyncService.syncModified(host(HOST, Role.USER, UserStatus.ACTIVE, 2L));
                        demoted.countDown();
                        awaitQuietly(release);
                    }));
            assertThat(demoted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> creating = pool.submit(() -> chatGroupRoomService.createRoom(HOST, recruit()));
            assertThatThrownBy(() -> creating.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            release.countDown();
            demoting.get(10, TimeUnit.SECONDS);

            assertThatThrownBy(() -> creating.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(ChatException.class)
                    .extracting(cause -> ((ChatException) cause).getErrorCode()).isEqualTo(ChatErrorCode.CHAT_NOT_ALLOWED);
            assertThat(chatRoomRepository.findCurrentGroup(RECRUIT_ID)).isEmpty();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static ChatRecruit recruit() {
        return new ChatRecruit(RECRUIT_ID, HOST, "주말 합방 모집", "RECRUITING");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private Long createRoom(Long hostId, Long recruitId) {
        return chatGroupRoomFacade.createRoom(hostId, recruitId).room().chatRoomId();
    }

    private ChatRoomStatus statusOf(Long roomId) {
        return chatRoomRepository.findById(roomId).orElseThrow().getStatus();
    }

    private List<Map<String, Object>> hostUnavailableNotices(Long roomId) {
        return sql.rows("""
                select id, content, sender_user_id, notification_data ->> 'hostUserId' as host_user_id from chats.chat_message
                where chat_room_id = :roomId and notification_kind = 'GROUP_HOST_UNAVAILABLE'""",
                Map.of("roomId", roomId));
    }

    private Long hostUnavailableLogs(Long roomId) {
        return sql.single("""
                select count(*) from chats.chat_room_log
                where chat_room_id = :roomId and event_type = 'HOST_UNAVAILABLE'""", Long.class,
                Map.of("roomId", roomId));
    }

    private static UserDto host(Long id, Role role, UserStatus status, Long version) {
        return new UserDto(id, "호스트" + id, role, status, version);
    }
}
