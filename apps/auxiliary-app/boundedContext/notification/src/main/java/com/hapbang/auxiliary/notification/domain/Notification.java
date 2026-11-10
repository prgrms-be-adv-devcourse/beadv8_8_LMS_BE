package com.hapbang.auxiliary.notification.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.util.Assert;

import com.hapbang.shared.jpa.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림 한 건(ND-025). 문구와 값을 함께 저장해 값으로 문구를 다시 만들 수 있다(ND-020). 지우지 않고 삭제 시각만 채운다(ND-022).
 * <p>
 * 동시성(#801 PR 리뷰): 읽은 시각·삭제 시각은 엔티티로 고치지 않고 저장소의 조건부 벌크 UPDATE로만 바꾼다. 엔티티로 바꾸는 것은
 * 파기 때 값·문구뿐이고, 그때는 행을 먼저 잠근다. {@code @DynamicUpdate}로 바뀐 칸만 써서 다른 칸을 덮어쓰지 않게 한 번 더 막는다.
 */
@Entity
@DynamicUpdate
@Table(name = "notification", schema = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long receiverUserId;

    private String kind;

    private String sourceEventType;

    private String sourceTargetId;

    private String targetType;

    private String targetId;

    @Column(columnDefinition = "text")
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    private String bundleKey;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "bigint[]")
    private Long[] mentionedUserIds;

    private Instant occurredAt;

    private Instant readAt;

    private Instant deletedAt;

    /**
     * 초안과 그 종류의 문구 템플릿으로 만든다. 값은 깊게 복사하고, 값의 닉네임 회원을 언급 회원 ID로 담는다(ND-051).
     *
     * @throws IllegalArgumentException 값에 형식 버전({@code v})이 없거나 필수 값이 없을 때
     */
    public static Notification create(NotificationDraft draft, Function<Map<String, Object>, String> renderer) {
        Assert.notNull(draft, "알림 초안이 필요합니다.");
        Assert.notNull(draft.receiverUserId(), "받는 사람이 필요합니다.");
        Assert.hasText(draft.kind(), "종류가 필요합니다.");
        Assert.notNull(draft.occurredAt(), "발생 시각이 필요합니다.");
        Assert.notNull(draft.payload(), "값이 필요합니다.");
        Assert.isTrue(draft.payload().containsKey(NotificationPayload.VERSION), "값에 형식 버전(v)이 필요합니다.");
        Notification notification = new Notification();
        notification.receiverUserId = draft.receiverUserId();
        notification.kind = draft.kind();
        notification.sourceEventType = draft.sourceEventType();
        notification.sourceTargetId = draft.sourceTargetId();
        notification.targetType = draft.targetType();
        notification.targetId = draft.targetId();
        notification.occurredAt = draft.occurredAt();
        notification.payload = copy(draft.payload(), NotificationPayload.nicknames(draft.payload()));
        notification.mentionedUserIds = NotificationPayload.nicknames(notification.payload).keySet().stream()
                .map(Long::valueOf).sorted().toArray(Long[]::new);
        notification.message = render(renderer, notification.payload);
        return notification;
    }

    /**
     * 파기된 회원의 닉네임을 값에서 {@code 탈퇴회원#{회원ID}}로 바꾸고 문구를 값으로 다시 만든다(ND-036·ND-051).
     *
     * @return 바꿨으면 true. 언급되지 않았거나 이미 바꿨으면 false(멱등)
     */
    public boolean maskNickname(long userId, Function<Map<String, Object>, String> renderer) {
        Map<String, String> nicknames = NotificationPayload.nicknames(payload);
        String key = String.valueOf(userId);
        String masked = NotificationPayload.maskedNickname(userId);
        if (!nicknames.containsKey(key) || masked.equals(nicknames.get(key))) {
            return false;
        }
        Map<String, String> changed = new LinkedHashMap<>(nicknames);
        changed.put(key, masked);
        // 새 맵으로 바꿔야 JSON 칸의 변경이 반영된다
        payload = copy(payload, changed);
        message = render(renderer, payload);
        return true;
    }

    private static Map<String, Object> copy(Map<String, Object> source, Map<String, String> nicknames) {
        Map<String, Object> copied = new LinkedHashMap<>(source);
        if (!nicknames.isEmpty() || source.containsKey(NotificationPayload.NICKNAMES)) {
            copied.put(NotificationPayload.NICKNAMES, new LinkedHashMap<>(nicknames));
        }
        return copied;
    }

    private static String render(Function<Map<String, Object>, String> renderer, Map<String, Object> payload) {
        String rendered = renderer.apply(payload);
        Assert.hasText(rendered, "문구가 비어 있습니다.");
        return rendered;
    }
}
