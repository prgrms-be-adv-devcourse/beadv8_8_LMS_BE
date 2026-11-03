package com.hapbang.chat.domain;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.util.Assert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채팅방. 1:1 방({@link ChatRoomType#DIRECT})과 모집용 단체방({@link ChatRoomType#GROUP})이 같은 테이블을 쓴다.
 * <ul>
 *     <li>{@code directKey}는 종료되지 않은 1:1 방에만 값이 있다. 유일 제약으로 같은 두 회원의 활성 방을 하나로 제한하고,
 *     방이 종료되면 비워서 다음 대화가 새 방에서 시작되게 한다.</li>
 *     <li>단체방 전용 값(모집글 ID·호스트·제목·게스트 정원·읽기 전용 사유·전환 시각·조회 만료 시각)은 1:1 방에서 null이다.
 *     모집글마다 현재 방(사용 중·일시 중지)은 하나뿐이다(부분 유일 인덱스 {@code uk_chat_room_group_current}).
 *     폐쇄한 방은 읽기 전용으로 남고 같은 모집글에 새 방을 만들 수 있다.</li>
 *     <li>단체방의 조회 만료는 조회 때 {@code historyExpiresAt}으로 정한다. 그 시각에 도달한 순간부터(같은 시각 포함) 참여자는
 *     볼 수 없다. 기록은 지우지 않는다.</li>
 *     <li>{@code lastSequence}는 방 단위 메시지 순번이다. 방을 잠근 상태에서만 발급한다.</li>
 *     <li>{@code currentRecipientSetId}·{@code activeMemberCount}는 참여 변경 때 방 잠금 아래 참여 행과 함께 바꾼다.
 *     메시지 전달 작업은 저장 시점의 현재 집합 버전을 참조한다.</li>
 * </ul>
 */
@Entity
@Table(name = "chat_room", schema = "chats",
        uniqueConstraints = @UniqueConstraint(name = "uk_chat_room_direct_key", columnNames = "direct_key"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom {

    /** 단체방을 읽기 전용으로 바꾼 뒤 참여자가 기록을 볼 수 있는 기간 (정책 8장) */
    public static final Duration GROUP_HISTORY_RETENTION = Duration.ofDays(30);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChatRoomType roomType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChatRoomStatus status;

    @Column(name = "direct_key")
    private String directKey;

    @Column(nullable = false)
    private long lastSequence;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime endedAt;

    private LocalDateTime deletedAt;

    /** 현재 불변 수신자 집합 버전. 없으면 메시지 전송 대상으로 열지 않는다 */
    @Column(name = "current_recipient_set_id")
    private Long currentRecipientSetId;

    /** 활성 참여자 수. 참여 행·현재 수신자 집합과 같은 트랜잭션에서 함께 바꾼다 */
    @Column(nullable = false)
    private int activeMemberCount;

    /** 단체방이 연결된 출연 모집글 ID (다른 모듈의 ID). 1:1 방은 null */
    private Long recruitId;

    /** 단체방 호스트(모집글 호스트) 회원 ID. 서버가 모집글에서 확인한 값이다. 1:1 방은 null */
    private Long ownerUserId;

    /** 단체방 제목. 생성 때 모집글 제목을 복사하고 초대 후보 검색 때 모집글 제목으로 맞춘다. 1:1 방은 null */
    private String title;

    /** 단체방 게스트 정원(호스트 제외). 생성 때의 설정값을 저장한다. 1:1 방은 null */
    private Integer maxGuestMembers;

    /** 읽기 전용이 된 사유. 사용 중인 방과 1:1 방은 null */
    @Enumerated(EnumType.STRING)
    private ChatRoomStatusReason statusReason;

    /** 읽기 전용으로 바뀐 시각 */
    private LocalDateTime readOnlyAt;

    /** 참여자 조회 만료 시각(읽기 전용 전환 시각 + 30×24시간). 이 시각부터 참여자는 볼 수 없다 */
    private LocalDateTime historyExpiresAt;

    /** 방 상태 버전. 상태가 바뀔 때마다 1 늘린다. 늦게 도착한 옛 상태 변경이 새 상태를 덮지 않게 비교하는 데 쓴다 */
    @Column(nullable = false)
    private int statusVersion;

    private ChatRoom(ChatRoomType roomType, String directKey, LocalDateTime now) {
        this.roomType = roomType;
        this.status = ChatRoomStatus.ACTIVE;
        this.directKey = directKey;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ChatRoom createDirect(Long userId, Long otherUserId, LocalDateTime now) {
        return new ChatRoom(ChatRoomType.DIRECT, directKeyOf(userId, otherUserId), now);
    }

    /**
     * 모집용 단체방을 만든다. 호스트·제목은 출연 앱에서 확인한 모집글 값이고, 정원은 생성 때의 설정값이다.
     */
    public static ChatRoom createGroup(Long recruitId, Long ownerUserId, String title, int maxGuestMembers,
            LocalDateTime now) {
        Assert.state(recruitId != null && ownerUserId != null, "모집글 ID와 호스트가 필요합니다.");
        Assert.state(title != null && !title.isBlank(), "단체방 제목이 비어 있습니다.");
        Assert.state(maxGuestMembers > 0, "게스트 정원은 1 이상이어야 합니다.");
        ChatRoom room = new ChatRoom(ChatRoomType.GROUP, null, now);
        room.recruitId = recruitId;
        room.ownerUserId = ownerUserId;
        room.title = title;
        room.maxGuestMembers = maxGuestMembers;
        return room;
    }

    public boolean isGroup() {
        return roomType == ChatRoomType.GROUP;
    }

    /** 단체방의 호스트인지. 1:1 방은 늘 false */
    public boolean isOwnedBy(Long userId) {
        return isGroup() && ownerUserId.equals(userId);
    }

    /**
     * 참여자가 조회·읽음·수신 확인·구독할 수 있는 방인지. 1:1 방은 종료되지 않은 방이고, 단체방은 사용 중·일시 중지·
     * 조회 만료 전의 읽기 전용 방이다.
     */
    public boolean isViewable(LocalDateTime now) {
        if (deletedAt != null) {
            return false;
        }
        if (!isGroup()) {
            return !isEnded();
        }
        return switch (status) {
            case ACTIVE, SUSPENDED -> true;
            case READ_ONLY -> !isHistoryExpired(now);
            case ENDED, EXPIRED -> false;
        };
    }

    /** 단체방 참여자의 조회 기간이 끝났는지. 만료 시각에 도달한 순간부터(같은 시각 포함) 끝난 것으로 본다 */
    public boolean isHistoryExpired(LocalDateTime now) {
        return historyExpiresAt != null && !now.isBefore(historyExpiresAt);
    }

    /** 응답에 보일 상태. 조회 기간이 끝난 읽기 전용 단체방은 {@link ChatRoomStatus#EXPIRED}다 */
    public ChatRoomStatus statusAt(LocalDateTime now) {
        return status == ChatRoomStatus.READ_ONLY && isHistoryExpired(now) ? ChatRoomStatus.EXPIRED : status;
    }

    /** 모집글의 현재 방(사용 중·일시 중지)인지. 현재 방이 있으면 같은 모집글에 새 방을 만들지 않는다 */
    public boolean isCurrentGroup() {
        return isGroup() && (status == ChatRoomStatus.ACTIVE || status == ChatRoomStatus.SUSPENDED);
    }

    /** 단체방에서 전송·초대·강퇴를 할 수 있는지. 사용 중일 때만이다 */
    public boolean isGroupActive() {
        return isGroup() && status == ChatRoomStatus.ACTIVE;
    }

    /** 호스트가 직접 폐쇄해 읽기 전용이 된 단체방인지 */
    public boolean isClosedByHost() {
        return isGroup() && status == ChatRoomStatus.READ_ONLY && statusReason == ChatRoomStatusReason.CLOSED_BY_HOST;
    }

    /**
     * 단체방의 참여 중인 게스트 수(오프라인 포함, 호스트 제외). 사용 중·일시 중지 방에서는 호스트가 나갈 수도 내보내질 수도 없으므로
     * 활성 참여자 수에서 호스트 한 명을 뺀다.
     */
    public int activeGuestCount() {
        Assert.state(isCurrentGroup(), "사용 중이거나 일시 중지된 단체방에서만 게스트 수를 셉니다.");
        return activeMemberCount - 1;
    }

    /**
     * 호스트가 단체방을 폐쇄한다. 참여자를 내보내지 않고 읽기 전용으로 바꾸며, 지금부터 30×24시간 뒤 참여자 조회를 만료한다.
     * 폐쇄 안내는 상태를 바꾸기 전에 저장한다(읽기 전용 방에는 순번을 발급하지 않는다).
     */
    public void closeByHost(LocalDateTime now) {
        Assert.state(isCurrentGroup(), "사용 중이거나 일시 중지된 단체방만 폐쇄할 수 있습니다.");
        toReadOnly(ChatRoomStatusReason.CLOSED_BY_HOST, now);
    }

    /**
     * 호스트가 탈퇴하거나 호스트 자격이 없는 역할이 되어 단체방을 읽기 전용으로 바꾼다. 폐쇄와 같이 참여자를 내보내지 않고 지금부터
     * 30×24시간 뒤 참여자 조회를 만료한다. 일시 중지된 방도 자격 상실이 우선해 읽기 전용이 된다. 안내는 상태를 바꾸기 전에 저장한다.
     */
    public void markHostUnavailable(LocalDateTime now) {
        Assert.state(isCurrentGroup(), "사용 중이거나 일시 중지된 단체방만 호스트 자격 상실로 바꿀 수 있습니다.");
        toReadOnly(ChatRoomStatusReason.HOST_UNAVAILABLE, now);
    }

    private void toReadOnly(ChatRoomStatusReason reason, LocalDateTime now) {
        this.status = ChatRoomStatus.READ_ONLY;
        this.statusReason = reason;
        this.readOnlyAt = now;
        this.historyExpiresAt = now.plus(GROUP_HISTORY_RETENTION);
        this.statusVersion++;
        this.updatedAt = now;
    }

    /**
     * 두 회원 ID를 정렬해 만든 키. 누가 먼저 요청해도 같은 값이다.
     */
    public static String directKeyOf(Long userId, Long otherUserId) {
        return Math.min(userId, otherUserId) + ":" + Math.max(userId, otherUserId);
    }

    public boolean isEnded() {
        return status == ChatRoomStatus.ENDED;
    }

    /**
     * 다음 메시지 순번을 발급한다. 단체방은 사용 중·일시 중지일 때만 발급한다(읽기 전용 방의 마지막 메시지는 폐쇄 안내다).
     */
    public long issueSequence() {
        Assert.state(!isEnded(), "종료된 채팅방에는 메시지 순번을 발급할 수 없습니다.");
        Assert.state(!isGroup() || isCurrentGroup(), "읽기 전용 단체방에는 메시지 순번을 발급할 수 없습니다.");
        return ++lastSequence;
    }

    /**
     * 지금 입장하는 참여자의 조회 시작점. 이미 있는 메시지는 볼 수 없다.
     */
    public long nextVisibleSequence() {
        return lastSequence + 1;
    }

    /**
     * 읽음 처리할 순번. 아직 발급하지 않은 순번까지 읽음 처리하지 않게 마지막 순번을 넘지 않는다.
     */
    public long readableSequence(long requestedSequence) {
        return Math.min(requestedSequence, lastSequence);
    }

    public boolean hasRecipientSet() {
        return currentRecipientSetId != null;
    }

    /**
     * 참여 변경 뒤 만든 불변 수신자 집합을 현재 버전으로 삼는다. 방을 잠근 트랜잭션 안에서 참여 행 변경과 함께 부른다.
     *
     * @param memberCount 그 집합의 항목 수(= 활성 참여자 수)
     */
    public void applyRecipientSet(Long recipientSetId, int memberCount, LocalDateTime now) {
        Assert.notNull(recipientSetId, "수신자 집합 ID가 필요합니다.");
        Assert.isTrue(memberCount >= 0, "활성 참여자 수는 0 이상이어야 합니다.");
        this.currentRecipientSetId = recipientSetId;
        this.activeMemberCount = memberCount;
        this.updatedAt = now;
    }

    public void end(LocalDateTime now) {
        Assert.state(!isGroup(), "단체방은 종료하지 않습니다.");
        if (isEnded()) {
            return;
        }
        this.status = ChatRoomStatus.ENDED;
        this.directKey = null;
        this.endedAt = now;
        this.updatedAt = now;
    }
}
