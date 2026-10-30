package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.hapbang.chat.domain.ChatDeadReason;
import com.hapbang.chat.domain.ChatPublishStatus;

/**
 * 자기 구독 단절로 멈춘 큐가 다시 열리는 조건 (재검증). 멈춘 큐는 새 작업을 읽지 않고, 미확인 작업의 시험 발행으로만 다시
 * 열린다. 시험 발행할 미확인 작업이 시험 발행 결과(구독자 수) 없이 끝나 사라지면(원본 누락 격리 등) 구독이 돌아와도 큐가 열리는지
 * 확인한다.
 */
@DirtiesContext
class ChatDeliveryPauseLivenessTest extends ChatDeliveryTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    private TransactionTemplate transactionTemplate;

    @DynamicPropertySource
    static void redisNamespace(DynamicPropertyRegistry registry) {
        uniqueNamespace(registry);
    }

    @Test
    void 멈춘_큐의_마지막_미확인_작업이_발행_결과_없이_격리돼도_구독이_돌아오면_새_작업을_발행한다() {
        UUID paused = send("끊기기 직전");
        relayAll(ChatJobStream.DELIVERY);
        fanout.subscribers(0);
        pollAll(ChatJobStream.DELIVERY, WORKER);
        assertThat(checkpoint(paused).getLastSubscriberCount()).isZero();
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isEqualTo(1);

        // 멈춘 동안 그 작업의 수신자 집합(헤더)이 사라져 시험 발행 차례에 원본 누락으로 격리된다. 외래 키 검사를 끄고 지웠다가
        // 되돌린다(테스트 DB 사용자는 superuser다)
        long setId = outbox(paused).getRecipientSetId();
        Map<String, Object> header = jdbcClient.sql("select * from chats.chat_recipient_set where id = :id")
                .param("id", setId)
                .query()
                .singleRow();
        List<Map<String, Object>> members = jdbcClient.sql("""
                        select recipient_set_id, user_id, participation_version
                        from chats.chat_recipient_set_member where recipient_set_id = :id
                        """)
                .param("id", setId)
                .query()
                .listOfRows();
        withoutConstraints(() -> {
            jdbcClient.sql("delete from chats.chat_recipient_set_member where recipient_set_id = :id")
                    .param("id", setId)
                    .update();
            jdbcClient.sql("delete from chats.chat_recipient_set where id = :id").param("id", setId).update();
        });
        await().atMost(WAIT).pollInterval(Duration.ofMillis(50)).until(() -> {
            processor.reclaim(ChatJobStream.DELIVERY, RECLAIMER, 10);
            return checkpoint(paused).getStatus() == ChatPublishStatus.DEAD;
        });
        assertThat(checkpoint(paused).getDeadReason()).isEqualTo(ChatDeadReason.SOURCE_MISSING);
        assertThat(pendingCount(ChatJobStream.DELIVERY)).isZero();
        withoutConstraints(() -> {
            jdbcClient.sql("""
                            insert into chats.chat_recipient_set (id, chat_room_id, set_version, member_count, created_at)
                            values (:id, :roomId, :version, :count, :createdAt)
                            """)
                    .param("id", header.get("id"))
                    .param("roomId", header.get("chat_room_id"))
                    .param("version", header.get("set_version"))
                    .param("count", header.get("member_count"))
                    .param("createdAt", header.get("created_at"))
                    .update();
            members.forEach(member -> jdbcClient.sql("""
                            insert into chats.chat_recipient_set_member (recipient_set_id, user_id, participation_version)
                            values (:setId, :userId, :version)
                            """)
                    .param("setId", member.get("recipient_set_id"))
                    .param("userId", member.get("user_id"))
                    .param("version", member.get("participation_version"))
                    .update());
        });

        // 구독이 돌아왔다. 재확인 간격 상한(200ms)·회수 min-idle(100ms)보다 충분히 오래 실행기처럼 돌린다
        fanout.subscribers(1);
        UUID afterRecovery = send("돌아온 뒤");
        relayAll(ChatJobStream.DELIVERY);
        for (int round = 0; round < 20; round++) {
            await().pollDelay(Duration.ofMillis(100)).atMost(WAIT).until(() -> true);
            processor.reclaim(ChatJobStream.DELIVERY, RECLAIMER, 10);
            processor.pollNew(ChatJobStream.DELIVERY, WORKER);
        }

        assertThat(checkpoint(afterRecovery).getStatus())
                .as("구독이 돌아온 뒤 2초 동안 새 작업이 발행되지 않으면 큐가 재시작 전까지 멈춰 있다")
                .isEqualTo(ChatPublishStatus.PUBLISHED);
    }

    private void withoutConstraints(Runnable statements) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("set local session_replication_role = replica").update();
            statements.run();
        });
    }
}
