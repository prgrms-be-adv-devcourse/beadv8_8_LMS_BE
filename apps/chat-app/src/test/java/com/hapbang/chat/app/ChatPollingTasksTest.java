package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

import com.hapbang.chat.global.config.ObservationConfig;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;

/**
 * 짧은 주기 polling 작업의 {@code @Scheduled} 실행은 trace를 만들지 않고, 1분 이상 주기 배치는 만든다 (#631, #663).
 * <p>
 * Spring은 {@code @Scheduled} 실행마다 {@code tasks.scheduled.execution} observation을 대상 객체로 만든
 * {@link ScheduledTaskObservationContext}와 함께 연다. 그 판정만 확인한다.
 */
class ChatPollingTasksTest {

    private static final String SCHEDULED = "tasks.scheduled.execution";

    private static final Pattern PLACEHOLDER_DEFAULT = Pattern.compile("^\\$\\{[^:}]+:(.*)}$");

    private final ObservationPredicate predicate = new ObservationConfig().noRootInfrastructureObservation();

    /**
     * 1분보다 짧은 주기의 {@code @Scheduled} 작업을 {@link ChatPollingTasks}에 넣지 않으면 실패한다 (#663).
     * 주기는 코드 기본값(플레이스홀더의 {@code :} 뒤)으로 본다. 운영 yaml·환경 변수가 덮어도 코드 기본값이 원본이다.
     */
    @Test
    void 일분보다_짧은_주기의_Scheduled_작업은_모두_polling_목록에_있다() throws Exception {
        // 컴포넌트 스캔은 @Conditional(스케줄링·실시간 켜짐)을 평가해 배치 클래스를 빼므로 클래스 파일을 직접 읽는다
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
        List<Class<?>> shortTasks = new ArrayList<>();
        for (Resource resource : resolver.getResources("classpath*:com/hapbang/chat/**/*.class")) {
            MetadataReader reader = readers.getMetadataReader(resource);
            if (!reader.getAnnotationMetadata().hasAnnotatedMethods(Scheduled.class.getName())) {
                continue;
            }
            Class<?> type = Class.forName(reader.getClassMetadata().getClassName());
            for (Method method : type.getDeclaredMethods()) {
                for (Scheduled scheduled : AnnotatedElementUtils.getMergedRepeatableAnnotations(method, Scheduled.class,
                        Schedules.class)) {
                    if (interval(scheduled).compareTo(Duration.ofMinutes(1)) < 0) {
                        shortTasks.add(type);
                    }
                }
            }
        }

        assertThat(shortTasks).as("검사가 짧은 주기 작업을 찾아야 한다")
                .contains(ChatRoomActivityBatch.class, ChatReadCountBatch.class, ChatPresenceHeartbeat.class);
        assertThat(shortTasks).as("짧은 주기 작업은 ChatPollingTasks에 넣는다").allMatch(ChatPollingTasks::isPolling);
    }

    private static Duration interval(Scheduled scheduled) {
        if (scheduled.fixedDelay() > 0) {
            return Duration.of(scheduled.fixedDelay(), scheduled.timeUnit().toChronoUnit());
        }
        if (scheduled.fixedRate() > 0) {
            return Duration.of(scheduled.fixedRate(), scheduled.timeUnit().toChronoUnit());
        }
        if (!scheduled.fixedDelayString().isEmpty()) {
            return DurationStyle.detectAndParse(defaultOf(scheduled.fixedDelayString()));
        }
        if (!scheduled.fixedRateString().isEmpty()) {
            return DurationStyle.detectAndParse(defaultOf(scheduled.fixedRateString()));
        }
        CronExpression cron = CronExpression.parse(defaultOf(scheduled.cron()));
        LocalDateTime first = cron.next(LocalDateTime.of(2026, 1, 1, 0, 0));
        return Duration.between(first, cron.next(first));
    }

    private static String defaultOf(String value) {
        Matcher matcher = PLACEHOLDER_DEFAULT.matcher(value);
        if (value.startsWith("${")) {
            assertThat(matcher.matches()).as("주기 플레이스홀더에 코드 기본값이 있어야 한다: " + value).isTrue();
            return matcher.group(1);
        }
        return value;
    }

    @Test
    void 일초_주기_묶음_작업과_하트비트는_trace를_만들지_않는다() throws Exception {
        assertThat(predicate.test(SCHEDULED, new ScheduledTaskObservationContext(
                new ChatRoomActivityBatch(null), ChatRoomActivityBatch.class.getDeclaredMethod("run")))).isFalse();
        assertThat(predicate.test(SCHEDULED, new ScheduledTaskObservationContext(
                new ChatReadCountBatch(null), ChatReadCountBatch.class.getDeclaredMethod("run")))).isFalse();
        assertThat(predicate.test(SCHEDULED, new ScheduledTaskObservationContext(
                new ChatPresenceHeartbeat(null, null, null), ChatPresenceHeartbeat.class.getDeclaredMethod("run"))))
                .isFalse();
    }

    @Test
    void 일분_이상_주기_배치는_trace를_만든다() throws Exception {
        assertThat(predicate.test(SCHEDULED, new ScheduledTaskObservationContext(
                new ChatGroupRoomBatch(null, null), ChatGroupRoomBatch.class.getDeclaredMethod("expireHistory"))))
                .isTrue();
    }

    @Test
    void 부모가_있는_observation은_막지_않는다() throws Exception {
        ScheduledTaskObservationContext polling = new ScheduledTaskObservationContext(
                new ChatRoomActivityBatch(null), ChatRoomActivityBatch.class.getDeclaredMethod("run"));
        polling.setParentObservation(Observation.start("parent", ObservationRegistry.create()));

        assertThat(predicate.test(SCHEDULED, polling)).isTrue();
        assertThat(predicate.test("spring.security.filterchains", withParent())).isTrue();
    }

    @Test
    void 부모_없는_Security_observation은_막는다() {
        assertThat(predicate.test("spring.security.filterchains", new Observation.Context())).isFalse();
        assertThat(predicate.test("http.server.requests", new Observation.Context())).isTrue();
    }

    private static Observation.Context withParent() {
        Observation.Context context = new Observation.Context();
        context.setParentObservation(Observation.start("parent", ObservationRegistry.create()));
        return context;
    }
}
