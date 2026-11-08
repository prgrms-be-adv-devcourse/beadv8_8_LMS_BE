package com.hapbang.chat.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.util.Assert;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 단체방 초대 요청의 결과 기록. 방·요청 ID({@code requestId})마다 한 행이다.
 * <ul>
 *     <li>보존 기간 안에 같은 요청 ID가 다시 오면 저장한 회원별 결과를 그대로 돌려주고 다시 초대하지 않는다. 그래서 옛 요청의 재시도가
 *     그 사이 나가거나 내보내진 회원을 다시 넣지 않는다.</li>
 *     <li>보존 기간이 지난 같은 요청 ID는 새 요청으로 처리하고 이 행의 결과를 새 결과로 바꾼다.</li>
 *     <li>방을 잠근 초대 트랜잭션에서만 읽고 쓴다. 참여 행·안내·전달 작업과 함께 커밋·롤백된다.</li>
 * </ul>
 */
@Entity
@Table(name = "chat_group_invite_request", schema = "chats",
        uniqueConstraints = @UniqueConstraint(name = "uk_chat_group_invite_request_room_request",
                columnNames = {"chat_room_id", "request_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatGroupInviteRequest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<ChatGroupInviteOutcome>> OUTCOMES = new TypeReference<>() {
    };

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_room_id", nullable = false)
    private Long chatRoomId;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(nullable = false)
    private Long requesterUserId;

    /** 회원별 결과 목록의 JSON. 요청 순서(중복 제거 뒤)다 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "results", nullable = false, columnDefinition = "jsonb")
    private String resultsJson;

    /** 결과를 남긴 시각. 보존 기간은 이 시각부터 센다 */
    @Column(nullable = false)
    private LocalDateTime recordedAt;

    private ChatGroupInviteRequest(Long chatRoomId, UUID requestId, Long requesterUserId,
            List<ChatGroupInviteOutcome> outcomes, LocalDateTime now) {
        this.chatRoomId = chatRoomId;
        this.requestId = requestId;
        apply(requesterUserId, outcomes, now);
    }

    public static ChatGroupInviteRequest record(Long chatRoomId, UUID requestId, Long requesterUserId,
            List<ChatGroupInviteOutcome> outcomes, LocalDateTime now) {
        Assert.state(chatRoomId != null && requestId != null, "방과 요청 ID가 필요합니다.");
        return new ChatGroupInviteRequest(chatRoomId, requestId, requesterUserId, outcomes, now);
    }

    /** 보존 기간 안의 결과인지. 기간이 끝나는 시각부터는 새 요청으로 본다 */
    public boolean isRetained(LocalDateTime now, Duration retention) {
        return now.isBefore(recordedAt.plus(retention));
    }

    /** 보존 기간이 지난 같은 요청 ID를 새 요청으로 처리한 결과로 바꾼다 */
    public void replace(Long requesterUserId, List<ChatGroupInviteOutcome> outcomes, LocalDateTime now) {
        apply(requesterUserId, outcomes, now);
    }

    public List<ChatGroupInviteOutcome> outcomes() {
        try {
            return JSON.readValue(resultsJson, OUTCOMES);
        } catch (JacksonException e) {
            // 서버가 쓴 값만 있으므로 오지 않는 경로다. 저장한 결과를 모른 채 다시 초대하지 않도록 실패로 둔다
            throw new IllegalStateException("저장한 초대 결과를 읽을 수 없습니다. id=" + id, e);
        }
    }

    private void apply(Long requesterUserId, List<ChatGroupInviteOutcome> outcomes, LocalDateTime now) {
        Assert.state(requesterUserId != null && outcomes != null, "요청한 회원과 결과가 필요합니다.");
        this.requesterUserId = requesterUserId;
        this.resultsJson = JSON.writeValueAsString(outcomes);
        this.recordedAt = now;
    }
}
