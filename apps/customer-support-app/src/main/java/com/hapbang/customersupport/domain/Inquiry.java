package com.hapbang.customersupport.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.util.Assert;

import com.hapbang.shared.jpa.BaseEntity;
import com.hapbang.shared.lecture.domain.LectureObjectionCommandType;
import com.hapbang.shared.lecture.domain.LectureObjectionFailureCode;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원의 1:1 문의. 관리자 답변({@link InquiryReply})을 거느리는 애그리게이트 루트다.
 * <ul>
 *     <li>답변은 {@link #reply}, {@link #editReply}, {@link #deleteReply}로만 다룬다.</li>
 *     <li>답변을 달면 답변 완료, 답변을 지워 남은 답변이 없으면 다시 답변 대기가 된다.</li>
 *     <li>답변은 문의와 함께 저장한다({@code cascade = PERSIST}). 문의·답변 본문은 분쟁 확인용으로 남기므로
 *     Hard Delete 하지 않고, 그래서 REMOVE는 전파하지 않는다. 삭제는 모두 Soft Delete다.</li>
 *     <li>문의 삭제는 답변 대기일 때만 된다. 그래서 삭제된 문의에는 남은 답변이 없다.</li>
 *     <li>강의 내용 다름 이의 제기 문의는 대상 강의 주문 항목을 가진다({@link #createLectureDispute}).
 *     관리자 판단({@link #resolveObjection})은 한 번만 하며, 판단 안내 답변을 함께 달아 답변 완료가 된다.
 *     판단한 문의는 답변을 모두 지워도 답변 완료로 남는다.</li>
 *     <li>이의 제기는 강의와 연동한다(#279, D60·D71). 접수는 강의 내부 HTTP로, 판단·철회는 고객센터 이벤트로 보내고,
 *     강의의 결과 이벤트로 끝난다. 그 사이 상태가 {@link ObjectionLinkStatus}이고, 지금 보낸 명령의 ID·내용 지문을 함께 둔다.
 *     판단은 강의가 반영한 뒤에만 기록한다({@link #applyResolved}).</li>
 * </ul>
 * 작성자는 회원 ID로만 참조한다. 생성일·수정일은 {@link BaseEntity}가 기록한다.
 */
@Entity
@Table(name = "inquiry", schema = "customersupport")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inquiry extends BaseEntity {

    public static final int TITLE_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InquiryCategory category;

    @Column(nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InquiryStatus status;

    @Enumerated(EnumType.STRING)
    private InquiryTargetType targetType;

    private Long targetId;

    @Enumerated(EnumType.STRING)
    private ObjectionResult objectionResult;

    private Long objectionResolvedBy;

    private LocalDateTime objectionResolvedAt;

    private LocalDateTime deletedAt;

    /** 강의 연동 상태. 이의 제기 문의에만 있다 */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ObjectionLinkStatus objectionLinkStatus;

    /** 지금(또는 마지막으로) 강의에 보낸 명령 ID. 예: {@code RESOLVE:12} */
    @Column(length = 100)
    private String objectionCommandId;

    /** 판단·철회 명령 내용의 지문. 같은 요청이 다시 오면 처리 중(202)·완료로, 다른 내용이면 충돌(409)로 본다 */
    @Column(length = 64)
    private String objectionCommandHash;

    /** 강의가 접수·판단·철회를 거절한 사유 */
    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private LectureObjectionFailureCode objectionFailureCode;

    /** 판단을 요청한 관리자. 강의가 반영하면 판단한 관리자({@link #objectionResolvedBy})가 된다 */
    private Long objectionRequestedBy;

    /** 강의가 반영하면 달 판단 안내 답변 */
    @Column(columnDefinition = "TEXT")
    private String objectionPendingReply;

    /** 인정한 이의 제기의 HP 반환 상태. 앞으로만 바뀐다 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private HpReturnStatus hpReturnStatus;

    @OneToMany(mappedBy = "inquiry", cascade = CascadeType.PERSIST)
    @OrderBy("id asc")
    private List<InquiryReply> replies = new ArrayList<>();

    private Inquiry(Long userId, InquiryCategory category, String title, String content) {
        this.userId = userId;
        this.category = category;
        this.title = title;
        this.content = content;
        this.status = InquiryStatus.WAITING;
    }

    /** 대상 없는 문의. 강의 내용 다름 이의 제기는 {@link #createLectureDispute}로 만든다 */
    public static Inquiry create(Long userId, InquiryCategory category, String title, String content) {
        Assert.state(userId != null, "작성자가 없습니다.");
        Assert.state(category != null, "문의 유형이 없습니다.");
        Assert.state(!category.requiresTarget(), "대상이 필요한 문의 유형입니다: " + category);
        Assert.state(hasText(title) && title.strip().length() <= TITLE_MAX_LENGTH, "문의 제목이 올바르지 않습니다.");
        Assert.state(hasText(content), "문의 내용이 비어 있습니다.");
        return new Inquiry(userId, category, title.strip(), content.strip());
    }

    /** 강의 내용 다름 이의 제기 문의. 대상은 이의 제기할 강의 주문 항목이다 */
    public static Inquiry createLectureDispute(Long userId, String title, String content, Long orderItemId) {
        Assert.state(userId != null, "작성자가 없습니다.");
        Assert.state(orderItemId != null, "이의 제기할 주문 항목이 없습니다.");
        Assert.state(hasText(title) && title.strip().length() <= TITLE_MAX_LENGTH, "문의 제목이 올바르지 않습니다.");
        Assert.state(hasText(content), "문의 내용이 비어 있습니다.");
        Inquiry inquiry = new Inquiry(userId, InquiryCategory.LECTURE_CONTENT_DISPUTE, title.strip(), content.strip());
        inquiry.targetType = InquiryTargetType.LECTURE_ORDER_ITEM;
        inquiry.targetId = orderItemId;
        inquiry.objectionLinkStatus = ObjectionLinkStatus.FILING;
        return inquiry;
    }

    public boolean isWrittenBy(Long userId) {
        return this.userId.equals(userId);
    }

    /** 답변 대기 중인지. 답변이 달리기 전에만 삭제할 수 있다 */
    public boolean isWaiting() {
        return status == InquiryStatus.WAITING;
    }

    public boolean isLectureDispute() {
        return category == InquiryCategory.LECTURE_CONTENT_DISPUTE;
    }

    public boolean isObjectionResolved() {
        return objectionResult != null;
    }

    /**
     * 이의 제기를 판단하고 판단 안내 답변을 단다(답변 완료). UseCase가 이의 제기 문의인지, 이미 판단했는지 먼저 검사한다.
     */
    public InquiryReply resolveObjection(Long adminUserId, boolean accepted, String replyContent, LocalDateTime now) {
        Assert.state(isLectureDispute(), "이의 제기 문의가 아닙니다.");
        Assert.state(!isObjectionResolved(), "이미 판단한 이의 제기입니다.");
        Assert.state(adminUserId != null, "판단한 관리자가 없습니다.");
        this.objectionResult = ObjectionResult.of(accepted);
        this.objectionResolvedBy = adminUserId;
        this.objectionResolvedAt = now;
        return reply(adminUserId, replyContent);
    }

    /** 저장해 ID가 생긴 뒤 접수 명령 ID({@code FILE:<문의 ID>})를 정한다 */
    public void assignFileCommand() {
        requireLinkStatus(ObjectionLinkStatus.FILING);
        Assert.state(id != null, "저장한 문의만 접수 명령을 만들 수 있습니다.");
        this.objectionCommandId = LectureObjectionCommandType.FILE.commandIdOf(id);
    }

    /** 강의가 접수했다. 접수 처리 중일 때만 바뀌고, 이미 끝났으면 그대로 둔다 */
    public boolean markFiled() {
        if (objectionLinkStatus != ObjectionLinkStatus.FILING) {
            return false;
        }
        this.objectionLinkStatus = ObjectionLinkStatus.FILED;
        return true;
    }

    /**
     * 강의가 접수를 거절했다. 요청 안에서 바로 거절되면 사용자는 문의가 생기지 않은 것으로 보므로 삭제하고({@code delete = true}),
     * 처리 중(202)을 본 뒤의 거절이면 문의를 남겨 "접수 불가 · 사유"로 보여 준다 (D60 (1)).
     */
    public boolean rejectFiling(LectureObjectionFailureCode failureCode, boolean delete, LocalDateTime now) {
        if (objectionLinkStatus != ObjectionLinkStatus.FILING) {
            return false;
        }
        this.objectionLinkStatus = ObjectionLinkStatus.FILE_REJECTED;
        this.objectionFailureCode = failureCode;
        if (delete) {
            this.deletedAt = now;
        }
        return true;
    }

    /** 판단 요청을 선점한다(접수됨 → 판단 처리 중). 판단은 강의가 반영한 뒤 {@link #applyResolved}로 기록한다 */
    public void requestResolution(Long adminUserId, String replyContent, String commandHash) {
        requireLinkStatus(ObjectionLinkStatus.FILED);
        Assert.state(adminUserId != null, "판단한 관리자가 없습니다.");
        Assert.state(hasText(replyContent), "판단 안내 답변이 비어 있습니다.");
        this.objectionLinkStatus = ObjectionLinkStatus.RESOLUTION_PENDING;
        this.objectionCommandId = LectureObjectionCommandType.RESOLVE.commandIdOf(id);
        this.objectionCommandHash = commandHash;
        this.objectionRequestedBy = adminUserId;
        this.objectionPendingReply = replyContent.strip();
    }

    /** 철회 요청을 선점한다(접수됨 → 철회 처리 중). 문의는 강의가 반영한 뒤 {@link #applyWithdrawn}에서 삭제된다 */
    public void requestWithdrawal(String commandHash) {
        requireLinkStatus(ObjectionLinkStatus.FILED);
        Assert.state(isWaiting(), "답변이 달린 문의는 철회할 수 없습니다.");
        this.objectionLinkStatus = ObjectionLinkStatus.WITHDRAWAL_PENDING;
        this.objectionCommandId = LectureObjectionCommandType.WITHDRAW.commandIdOf(id);
        this.objectionCommandHash = commandHash;
    }

    /**
     * 강의가 판단을 반영했다. 지금 처리 중인 판단 명령일 때만 판단·답변을 기록하고 답변 완료가 된다.
     * 인정이면 HP 반환 상태를 반환 처리 중 이상으로 둔다(반환 이벤트가 먼저 왔으면 그 상태를 유지).
     *
     * @return 반영했는지. 다른 명령의 결과거나 이미 반영했으면 {@code false}
     */
    public boolean applyResolved(String commandId, boolean accepted, LocalDateTime now) {
        if (objectionLinkStatus != ObjectionLinkStatus.RESOLUTION_PENDING || !commandId.equals(objectionCommandId)) {
            return false;
        }
        resolveObjection(objectionRequestedBy, accepted, objectionPendingReply, now);
        this.objectionLinkStatus = ObjectionLinkStatus.RESOLVED;
        this.objectionPendingReply = null;
        if (accepted) {
            this.hpReturnStatus = HpReturnStatus.advance(hpReturnStatus, HpReturnStatus.PENDING);
        }
        return true;
    }

    /** 강의가 철회를 반영했다. 지금 처리 중인 철회 명령일 때만 문의를 삭제한다 */
    public boolean applyWithdrawn(String commandId, LocalDateTime now) {
        if (objectionLinkStatus != ObjectionLinkStatus.WITHDRAWAL_PENDING || !commandId.equals(objectionCommandId)) {
            return false;
        }
        this.objectionLinkStatus = ObjectionLinkStatus.WITHDRAWN;
        delete(now);
        return true;
    }

    /** 강의가 판단·철회를 거절했다. 지금 처리 중인 그 명령일 때만 실패로 바꾼다 */
    public boolean applyRefused(String commandId, LectureObjectionCommandType commandType,
                                LectureObjectionFailureCode failureCode) {
        ObjectionLinkStatus pending = commandType == LectureObjectionCommandType.RESOLVE
                ? ObjectionLinkStatus.RESOLUTION_PENDING : ObjectionLinkStatus.WITHDRAWAL_PENDING;
        if (objectionLinkStatus != pending || !commandId.equals(objectionCommandId)) {
            return false;
        }
        this.objectionLinkStatus = commandType == LectureObjectionCommandType.RESOLVE
                ? ObjectionLinkStatus.RESOLUTION_FAILED : ObjectionLinkStatus.WITHDRAWAL_FAILED;
        this.objectionFailureCode = failureCode;
        return true;
    }

    /** HP 반환 상태를 앞으로만 바꾼다. 판단 결과보다 반환 이벤트가 먼저 올 수 있다 */
    public void advanceHpReturn(HpReturnStatus next) {
        Assert.state(isLectureDispute(), "이의 제기 문의가 아닙니다.");
        this.hpReturnStatus = HpReturnStatus.advance(hpReturnStatus, next);
    }

    /** 지금 명령과 같은 내용인지 */
    public boolean hasCommandHash(String commandHash) {
        return commandHash.equals(objectionCommandHash);
    }

    /** 관리자 답변을 달고 답변 완료로 바꾼다. 답변은 여러 개 달 수 있다 */
    public InquiryReply reply(Long adminUserId, String content) {
        Assert.state(deletedAt == null, "삭제된 문의에는 답변할 수 없습니다.");
        InquiryReply reply = InquiryReply.create(this, adminUserId, content);
        replies.add(reply);
        this.status = InquiryStatus.ANSWERED;
        return reply;
    }

    /** 삭제되지 않은 답변 중 이 ID가 있는지 */
    public boolean hasReply(Long replyId) {
        return findReply(replyId).isPresent();
    }

    /** 답변 내용을 고친다. 상태는 그대로다 */
    public void editReply(Long replyId, String content) {
        replyOf(replyId).edit(content);
    }

    /** 답변을 지운다(Soft Delete). 남은 답변이 없으면 답변 대기로 되돌린다. 판단한 이의 제기는 답변 완료로 남는다 */
    public void deleteReply(Long replyId, LocalDateTime now) {
        replyOf(replyId).delete(now);
        if (getReplies().isEmpty() && !isObjectionResolved()) {
            this.status = InquiryStatus.WAITING;
        }
    }

    /** 삭제되지 않은 답변 (오래된 답변부터). 밖에서 바꿀 수 없다 */
    public List<InquiryReply> getReplies() {
        return replies.stream().filter(reply -> !reply.isDeleted()).toList();
    }

    /** Soft Delete. 본문은 분쟁 확인용으로 남는다 */
    public void delete(LocalDateTime now) {
        Assert.state(isWaiting(), "답변이 달린 문의는 삭제할 수 없습니다.");
        Assert.state(deletedAt == null, "이미 삭제된 문의입니다.");
        this.deletedAt = now;
    }

    private Optional<InquiryReply> findReply(Long replyId) {
        return getReplies().stream().filter(reply -> Objects.equals(reply.getId(), replyId)).findFirst();
    }

    private InquiryReply replyOf(Long replyId) {
        Assert.state(deletedAt == null, "삭제된 문의의 답변은 바꿀 수 없습니다.");
        return findReply(replyId).orElseThrow(() -> new IllegalStateException("답변이 없습니다: " + replyId));
    }

    private void requireLinkStatus(ObjectionLinkStatus expected) {
        Assert.state(objectionLinkStatus == expected,
                "이의 제기 연동 상태가 " + expected + "가 아닙니다. inquiryId=" + id + ", status=" + objectionLinkStatus);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
