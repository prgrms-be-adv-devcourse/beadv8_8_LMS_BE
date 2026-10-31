package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import com.hapbang.chat.app.dto.ChatUserSyncResult;
import com.hapbang.chat.app.dto.ChatUserSyncResult.Outcome;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.fixture.ChatNativeSql;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

import jakarta.persistence.EntityManager;

/**
 * 회원 반영과 연결 재검사 작업 기록의 규칙을 Kafka 없이 서비스로 검증한다(계약 control-recording.md).
 * 제어 outbox·발행 체크포인트는 JPA 엔티티가 아니라 공통 DB 정리에 포함되지 않으므로 테스트마다 직접 지운다.
 */
@IntegrationTest
class ChatUserSyncServiceTest {

    @Autowired
    private ChatUserSyncService chatUserSyncService;

    @Autowired
    private ChatUserRepository chatUserRepository;

    private ChatNativeSql sql;

    @Autowired
    void setUpSql(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        sql = ChatNativeSql.of(entityManager, transactionManager);
    }

    @BeforeEach
    @AfterEach
    void clearControlTables() {
        sql.update("delete from chats.chat_publish_checkpoint");
        sql.update("delete from chats.chat_control_outbox");
    }

    @Test
    void 가입은_만들기만_하고_채팅할_수_없어도_제어_작업을_남기지_않는다() {
        ChatUserSyncResult result = chatUserSyncService.syncJoined(user(USER_ID, Role.USER, UserStatus.ACTIVE, 1L));

        assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(result.canChat()).isFalse();
        assertThat(controlRows(USER_ID)).isEmpty();
    }

    @Test
    void 변경으로_채팅할_수_없게_되면_변경_사유로_제어_작업과_체크포인트를_남긴다() {
        chatUserSyncService.syncJoined(user(HOST_ID, Role.INFLUENCER, UserStatus.ACTIVE, 1L));

        ChatUserSyncResult result = chatUserSyncService.syncModified(
                user(HOST_ID, Role.INFLUENCER, UserStatus.WITHDRAWN, 2L));

        assertThat(result).extracting(ChatUserSyncResult::outcome, ChatUserSyncResult::memberVersion,
                ChatUserSyncResult::canChat).containsExactly(Outcome.UPDATED, 2L, false);
        assertThat(controlRows(HOST_ID)).singleElement().satisfies(row -> {
            assertThat(row.get("member_version")).isEqualTo(2L);
            assertThat(row.get("reason")).isEqualTo("MEMBER_MODIFIED");
        });
        assertThat(sql.single("""
                select count(*) from chats.chat_publish_checkpoint p
                join chats.chat_control_outbox o on o.control_id = p.job_id
                where o.user_id = :userId and p.consumer_group = 'chat-control-v1' and p.status = 'PENDING'""",
                Long.class, Map.of("userId", HOST_ID))).isEqualTo(1L);
    }

    @Test
    void 옛_버전_변경은_건너뛰고_제어_작업을_남기지_않는다() {
        chatUserSyncService.syncJoined(user(HOST_ID, Role.INFLUENCER, UserStatus.ACTIVE, 5L));

        ChatUserSyncResult result = chatUserSyncService.syncModified(
                user(HOST_ID, Role.USER, UserStatus.ACTIVE, 4L));

        assertThat(result.outcome()).isEqualTo(Outcome.SKIPPED_STALE);
        assertThat(result.canChat()).isTrue();
        assertThat(controlRows(HOST_ID)).isEmpty();
    }

    @Test
    void 복제본이_없으면_변경으로_만들지_않고_실패한다() {
        assertThatThrownBy(() -> chatUserSyncService.syncModified(user(GUEST_ID, Role.INFLUENCER, UserStatus.ACTIVE, 1L)))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.CHAT_USER_NOT_FOUND);

        assertThat(chatUserRepository.findById(GUEST_ID)).isEmpty();
        assertThat(controlRows(GUEST_ID)).isEmpty();
    }

    @Test
    void 백필은_없으면_만들고_있으면_버전_규칙으로_갱신하며_채팅_불가면_백필_사유로_남긴다() {
        assertThat(chatUserSyncService.syncBackfill(user(USER_ID, Role.USER, UserStatus.ACTIVE, 1L)).outcome())
                .isEqualTo(Outcome.CREATED);
        chatUserSyncService.syncJoined(user(HOST_ID, Role.INFLUENCER, UserStatus.ACTIVE, 1L));

        assertThat(chatUserSyncService.syncBackfill(user(HOST_ID, Role.INFLUENCER, UserStatus.SUSPENDED, 3L)).outcome())
                .isEqualTo(Outcome.UPDATED);
        assertThat(chatUserSyncService.syncBackfill(user(HOST_ID, Role.INFLUENCER, UserStatus.ACTIVE, 2L)).outcome())
                .isEqualTo(Outcome.SKIPPED_STALE);

        assertThat(controlRows(USER_ID)).isEmpty();
        assertThat(controlRows(HOST_ID)).singleElement().satisfies(row -> {
            assertThat(row.get("member_version")).isEqualTo(3L);
            assertThat(row.get("reason")).isEqualTo("MEMBER_BACKFILL");
        });
    }

    private static UserDto user(Long id, Role role, UserStatus status, Long version) {
        return new UserDto(id, "회원" + id, role, status, version);
    }

    private List<Map<String, Object>> controlRows(Long userId) {
        return sql.rows("select member_version, reason from chats.chat_control_outbox where user_id = :userId",
                Map.of("userId", userId));
    }
}
