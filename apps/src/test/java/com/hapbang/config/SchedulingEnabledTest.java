package com.hapbang.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.test.annotation.DirtiesContext;

import com.hapbang.testsupport.PostgresTestcontainersConfig;

// 앱 설정(application.yaml)으로 배치가 주기대로 실행되는지 확인한다.
// 켠 컨텍스트가 캐시에 남으면 다른 테스트를 하는 동안에도 배치가 돌므로 끝나면 닫는다
@SpringBootTest(properties = "hapbang.scheduling.enabled=true")
@Import({PostgresTestcontainersConfig.class, SchedulingProbe.class})
@DirtiesContext
class SchedulingEnabledTest {

    @Autowired
    SchedulingProbe.ProbeBatch probeBatch;

    @Autowired
    ThreadPoolTaskScheduler taskScheduler;

    @Autowired
    ScheduledTaskHolder scheduledTaskHolder;

    @Test
    void 배치_빈의_Scheduled_메서드가_주기대로_실행된다() {
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(probeBatch.threadNames).hasSizeGreaterThanOrEqualTo(2));
    }

    @Test
    void 배치는_전용_스케줄러_스레드에서_실행된다() {
        // 채팅 STOMP의 messageBrokerTaskScheduler(MessageBroker-)가 아니라 spring.task.scheduling 설정을 따른 스케줄러
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(probeBatch.threadNames).isNotEmpty());
        assertThat(probeBatch.threadNames).allSatisfy(name -> assertThat(name).startsWith("scheduling-"));
    }

    @Test
    void 강의_배치가_정한_cron으로_등록된다() {
        // 모듈 테스트에서는 배치 빈이 꺼져 있어 등록 여부와 cron 해석은 여기서만 확인할 수 있다
        assertThat(scheduledTaskHolder.getScheduledTasks())
                .extracting(ScheduledTask::getTask)
                .filteredOn(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                .extracting(Task::toString, CronTask::getExpression)
                .contains(
                        tuple("com.hapbang.lecture.app.OrderAutoConfirmBatch.run", "0 */10 * * * *"),
                        tuple("com.hapbang.lecture.app.OrderPayoutBatch.run", "0 0 1 * * *"));
    }

    @Test
    void 회원_팔로워_갱신_배치가_정한_cron으로_등록된다() {
        assertThat(scheduledTaskHolder.getScheduledTasks())
                .extracting(ScheduledTask::getTask)
                .filteredOn(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                .extracting(Task::toString, CronTask::getExpression)
                .contains(tuple("com.hapbang.member.in.MemberFollowerRefreshBatch.run", "0 0 10 * * *"));
    }

    @Test
    void 스케줄러_스레드는_4개다() {
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
    }
}
