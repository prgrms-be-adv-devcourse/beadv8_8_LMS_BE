package com.hapbang.auxiliary.notification.in.event;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import com.hapbang.auxiliary.notification.app.LectureObjectionAlerts;
import com.hapbang.auxiliary.notification.app.NotificationAlertService;
import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.in.event.kafka.ObjectionLegacyFormat;
import com.hapbang.auxiliary.notification.in.event.kafka.RejectedEventException;
import com.hapbang.auxiliary.notification.in.event.kafka.SkippablePayloads;
import com.hapbang.auxiliary.notification.in.event.kafka.SkippedEventLog;
import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnActionRequiredEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionWithdrawnEvent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 강의 이벤트({@code lecture.events.v1}, 그룹 {@code auxiliary-app.notification-lecture-alert})로 이의 제기 결과 4종(판단·반환
 * 완료·철회·반환 지연) 알림을 만드는 진입 어댑터(N6, ND-082·ND-087).
 * <ul>
 *     <li>값을 바이트로 받는다(ND-096). #818 이전 형식인지 원본 JSON의 키로 판별해야 하는데({@link ObjectionLegacyFormat},
 *     ND-086), 레코드로 바꾸면 키 없음과 {@code null} 값이 같아진다.</li>
 *     <li>{@code __TypeId__} 헤더가 없거나 패키지가 강의 이벤트 신뢰 패키지가 아니면 재시도 없이 DLT로 보낸다. 신뢰 패키지 안의 다른
 *     종류(추천용 동기화 등, 클래스패스에 없는 새 종류 포함)는 본문을 읽지 않고 건너뛴다.</li>
 *     <li>#818 이전 형식은 알림 없이 커밋하고 로그(종류마다 1·2·4…번째만 INFO)와 지표 {@value #LEGACY_SKIPPED_METRIC}를 남긴다. 판별할 수 없으면(값이
 *     {@code null}, 일부 키만 없음) 검증 실패로 재시도 없이 DLT로 보낸다. 읽을 수 없는 본문도 같다.</li>
 *     <li>컨테이너는 첫 회원 복제본 Backfill이 끝나면 시작하고(ND-078), 알림 시작 시각 전 사건은 저장하지 않는다(ND-076).</li>
 *     <li>저장 실패(복제본 없음, DB 장애)는 블로킹 재시도 뒤 DLT로 간다(ND-010·ND-035).</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty(name = NotificationKafkaConstants.LECTURE_EVENTS_ENABLED, matchIfMissing = true)
public class NotificationLectureEventListener {

    /** 리스너 컨테이너 ID(소비 그룹 이름과 다르다) */
    public static final String LISTENER_ID = "notification-lecture-events";
    /** #818 이전 형식이라 알림 없이 넘긴 레코드 수 */
    public static final String LEGACY_SKIPPED_METRIC = "hapbang.notification.alert.legacy.skipped";
    /** 발행 앱(spring-kafka JSON 직렬화)이 싣는 타입 헤더 */
    static final String TYPE_ID_HEADER = "__TypeId__";

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final Map<String, Class<?>> OBJECTION_EVENTS = Stream.<Class<?>>of(
                    LectureObjectionResolvedEvent.class, LectureObjectionReturnCompletedEvent.class,
                    LectureObjectionWithdrawnEvent.class, LectureObjectionReturnActionRequiredEvent.class)
            .collect(Collectors.toUnmodifiableMap(Class::getName, Function.identity()));

    private final NotificationAlertService alerts;
    private final NotificationKafkaConsumerSettings settings;
    private final SkippedEventLog skippedLog = new SkippedEventLog(log);
    private final ObjectProvider<MeterRegistry> meters;
    /** #818 이전 형식 종류별 누적(4종뿐이라 크기가 정해져 있다) */
    private final Map<Class<?>, AtomicLong> legacyCounts = new ConcurrentHashMap<>();

    public NotificationLectureEventListener(NotificationAlertService alerts,
            @Qualifier(NotificationKafkaConfig.LECTURE_EVENT_SETTINGS) NotificationKafkaConsumerSettings settings,
            ObjectProvider<MeterRegistry> meters) {
        this.alerts = alerts;
        this.settings = settings;
        this.meters = meters;
    }

    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = "#{__listener.topic}",
            groupId = "#{__listener.groupId}", containerFactory = NotificationKafkaConfig.LECTURE_EVENT_CONTAINER_FACTORY)
    public void on(ConsumerRecord<String, byte[]> record) {
        if (record.value() == null) {
            skipped(SkippablePayloads.TOMBSTONE, record);
            return;
        }
        String typeId = typeId(record);
        if (typeId == null) {
            throw new RejectedEventException("이벤트 타입 헤더가 없습니다.", null);
        }
        if (!NotificationKafkaConstants.LECTURE_EVENTS_TRUSTED_PACKAGE.equals(ClassUtils.getPackageName(typeId))) {
            throw new RejectedEventException("이벤트 신뢰 패키지 밖의 타입입니다: " + typeId, null);
        }
        Class<?> type = OBJECTION_EVENTS.get(typeId);
        if (type == null) {
            skipped(typeId, record);
            return;
        }
        switch (classify(type, record.value())) {
            case LEGACY -> skipLegacy(type, record);
            case UNDETERMINED -> throw new RejectedEventException(
                    "이의 제기 결과 이벤트에 필수 값이 비었는데 #818 이전 형식인지 알 수 없습니다: " + type.getSimpleName(), null);
            case CURRENT -> handle(read(type, record.value()));
        }
    }

    /** 판단은 구매자·판매자 2건이다. 하나가 저장에 실패해 다시 처리하면 먼저 저장한 건은 중복으로 건너뛴다(ND-019) */
    private void handle(Object event) {
        drafts(event).forEach(alerts::create);
    }

    /** 초안으로 바꾼다. 필수 값이 빈 이벤트(검증 실패)는 재시도 없이 DLT로 보낸다(ND-086). 저장 단계의 오류는 바꾸지 않는다 */
    private static List<NotificationDraft> drafts(Object event) {
        try {
            return switch (event) {
                case LectureObjectionResolvedEvent resolved -> LectureObjectionAlerts.resolved(resolved);
                case LectureObjectionReturnCompletedEvent completed ->
                        List.of(LectureObjectionAlerts.returnCompleted(completed));
                case LectureObjectionWithdrawnEvent withdrawn -> List.of(LectureObjectionAlerts.withdrawn(withdrawn));
                case LectureObjectionReturnActionRequiredEvent delayed ->
                        List.of(LectureObjectionAlerts.returnActionRequired(delayed));
                default -> throw new IllegalStateException("처리기가 없는 이의 제기 이벤트입니다: " + event.getClass());
            };
        } catch (IllegalArgumentException e) {
            throw new RejectedEventException("알림을 만들 수 없는 이의 제기 결과 이벤트입니다.", e);
        }
    }

    private static ObjectionLegacyFormat classify(Class<?> type, byte[] json) {
        try {
            return ObjectionLegacyFormat.classify(type, json);
        } catch (JacksonException e) {
            throw new RejectedEventException("이벤트 본문을 읽을 수 없습니다: " + type.getSimpleName(), e);
        }
    }

    private static Object read(Class<?> type, byte[] json) {
        try {
            return JSON.readValue(json, type);
        } catch (JacksonException e) {
            throw new RejectedEventException("이벤트 본문을 읽을 수 없습니다: " + type.getSimpleName(), e);
        }
    }

    /**
     * #818 이전 형식을 넘긴 로그와 지표(ND-086). 과거 이벤트를 다시 읽을 때 몰려 와도 넘치지 않게 종류마다 1·2·4·8…번째(2의
     * 거듭제곱)만 INFO, 나머지는 DEBUG다({@link SkippedEventLog}와 같은 방식). 계수는 메모리에만 있다
     */
    private void skipLegacy(Class<?> type, ConsumerRecord<String, byte[]> record) {
        long count = legacyCounts.computeIfAbsent(type, ignored -> new AtomicLong()).incrementAndGet();
        if (Long.bitCount(count) == 1) {
            log.info("#818 이전 형식의 이의 제기 결과라 알림을 만들지 않았다 type={}, key={}, partition={}, offset={}, 누적={}",
                    type.getSimpleName(), record.key(), record.partition(), record.offset(), count);
        } else {
            log.debug("#818 이전 형식의 이의 제기 결과라 알림을 만들지 않았다 type={}, key={}, partition={}, offset={}, 누적={}",
                    type.getSimpleName(), record.key(), record.partition(), record.offset(), count);
        }
        meters.ifAvailable(registry -> Counter.builder(LEGACY_SKIPPED_METRIC)
                .description("#818 이전 형식이라 알림 없이 넘긴 이의 제기 결과 이벤트 수")
                .tag("type", type.getSimpleName())
                .register(registry)
                .increment());
    }

    private void skipped(String type, ConsumerRecord<String, byte[]> record) {
        skippedLog.skipped(type, record.key(), record.partition(), record.offset());
    }

    private static String typeId(ConsumerRecord<String, byte[]> record) {
        Header header = record.headers().lastHeader(TYPE_ID_HEADER);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    /** {@code @KafkaListener}의 토픽(SpEL {@code __listener.topic}) */
    public String getTopic() {
        return settings.topic();
    }

    /** {@code @KafkaListener}의 소비자 그룹(SpEL {@code __listener.groupId}) */
    public String getGroupId() {
        return settings.groupId();
    }
}
