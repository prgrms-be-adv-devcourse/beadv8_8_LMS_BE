package com.hapbang.chat.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.util.Assert;

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
 *     <li>보존 기간이 지난 같은 요청 ID는 새 요청으로 처리한다. 이 행을 지우고 새 행을 만들어 새 ID를 받는다. 이 ID가 초대 요청 ID로
 *     알림센터용 초대 이벤트의 사건 ID에 들어가므로, 같은 방에서 같은 요청 ID를 다시 받아도 초대 요청 ID는 겹치지 않는다.</li>
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
            String resultsJson, LocalDateTime now) {
        Assert.state(requesterUserId != null && resultsJson != null, "요청한 회원과 결과가 필요합니다.");
        this.chatRoomId = chatRoomId;
        this.requestId = requestId;
        this.requesterUserId = requesterUserId;
        this.resultsJson = resultsJson;
        this.recordedAt = now;
    }

    /**
     * @param resultsJson 회원별 결과 목록({@link ChatGroupInviteOutcome})의 JSON. 요청 순서(중복 제거 뒤)다
     */
    public static ChatGroupInviteRequest record(Long chatRoomId, UUID requestId, Long requesterUserId,
            String resultsJson, LocalDateTime now) {
        Assert.state(chatRoomId != null && requestId != null, "방과 요청 ID가 필요합니다.");
        return new ChatGroupInviteRequest(chatRoomId, requestId, requesterUserId, resultsJson, now);
    }

    /** 보존 기간 안의 결과인지. 기간이 끝나는 시각부터는 새 요청으로 본다 */
    public boolean isRetained(LocalDateTime now, Duration retention) {
        return now.isBefore(recordedAt.plus(retention));
    }
}
