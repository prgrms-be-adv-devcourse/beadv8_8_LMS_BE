package com.hapbang.lecture.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.SQLRestriction;

import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 강의 상품과 검수 상태.
 * <ul>
 *     <li>등록하면 검수 대기로 시작하고, 관리자가 승인하면 판매 중, 반려하면 사유를 남긴다.</li>
 *     <li>영상·자료는 반려된 강의만 바꿀 수 있다(D11). 검수 대기 중에는 관리자가 보는 내용이 바뀌지 않고,
 *     판매 중에는 구매자가 보는 내용이 바뀌지 않는다. 판매 중 강의를 고치려면 삭제 후 다시 등록한다.</li>
 *     <li>제목·소개·가격·썸네일은 검수 대상이 아니므로 삭제 전이면 언제든 바꿀 수 있다.</li>
 *     <li>콘텐츠는 이 애그리거트를 통해서만 바꾼다. 교체된 콘텐츠는 Soft Delete로 남긴다.</li>
 * </ul>
 */
@Entity
@Table(name = "lecture", schema = "lectures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Lecture {

    private static final long PRICE_UNIT_HP = 1_000L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sellerUserId;

    private Long thumbnailFileId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private long priceHp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LectureStatus status;

    @Column(columnDefinition = "text")
    private String rejectionReason;

    @Column(nullable = false)
    private LocalDateTime inspectionRequestedAt;

    private LocalDateTime inspectedAt;

    private Long inspectedBy;

    /**
     * 검수 회차 (#872, D85). 등록이 1회차이고 재검수를 신청할 때마다 1 오른다. 검수 승인·반려 이벤트에 실려,
     * 같은 강의의 몇 번째 검수 결과인지 가린다(알림 ND-045). 검수 이력 엔티티가 없어 강의에 저장한다
     */
    @Column(nullable = false)
    private int inspectionCount;

    @Version
    private Long version;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    // 저장만 전파한다. Soft Delete 원칙상 콘텐츠를 물리 삭제하는 경로(REMOVE)는 열어 두지 않는다.
    // 동기화 내부 API(#712)는 한 페이지(최대 500개) 강의의 콘텐츠를 강의마다 따로 읽지 않고 한 번에 읽는다
    @OneToMany(mappedBy = "lecture", cascade = CascadeType.PERSIST)
    @SQLRestriction("deleted_at is null")
    @OrderBy("sequence")
    @BatchSize(size = 500)
    private List<LectureContent> contents = new ArrayList<>();

    private Lecture(Long sellerUserId, String title, String description, long priceHp, Long thumbnailFileId,
                    LocalDateTime now) {
        this.sellerUserId = sellerUserId;
        this.title = title;
        this.description = description;
        this.priceHp = priceHp;
        this.thumbnailFileId = thumbnailFileId;
        this.status = LectureStatus.PENDING_INSPECTION;
        this.inspectionRequestedAt = now;
        this.inspectionCount = 1;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Lecture register(
            Long sellerUserId,
            String title,
            String description,
            long priceHp,
            Long thumbnailFileId,
            List<LectureContentSpec> contentSpecs,
            LocalDateTime now
    ) {
        if (sellerUserId == null) {
            throw new LectureException(LectureErrorCode.INVALID_REQUEST);
        }
        validateInfo(title, priceHp);
        Lecture lecture = new Lecture(sellerUserId, title, description, priceHp, thumbnailFileId, now);
        lecture.contents.addAll(lecture.createContents(contentSpecs, now));
        return lecture;
    }

    public List<LectureContent> getContents() {
        return List.copyOf(contents);
    }

    /** 현재 콘텐츠 중에서 찾는다. 교체되어 지워진 콘텐츠나 다른 강의의 콘텐츠면 {@code LECTURE_CONTENT_NOT_FOUND} */
    public LectureContent findContent(Long contentId) {
        return contents.stream()
                .filter(content -> content.getId().equals(contentId))
                .findFirst()
                .orElseThrow(() -> new LectureException(LectureErrorCode.LECTURE_CONTENT_NOT_FOUND));
    }

    public boolean isSeller(Long userId) {
        return sellerUserId.equals(userId);
    }

    public boolean isOnSale() {
        return !isDeleted() && status == LectureStatus.ON_SALE;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * AI 추천용 강의 (#712, D82). 이벤트와 동기화 내부 API가 같은 값을 쓰도록 여기서만 만든다. 버전이 맞으려면 flush 뒤에 부른다.
     * 추천해도 되는지({@code onSale})는 강의가 판단한다. 콘텐츠는 삭제되지 않은 것만 순서대로 싣는다
     */
    public LectureSyncDto toSyncDto() {
        return new LectureSyncDto(id, sellerUserId, title, description, priceHp, isOnSale(), isDeleted(),
                contents.stream()
                        .map(content -> new LectureContentSyncDto(content.getId(), content.getTitle(),
                                content.getSequence()))
                        .toList(),
                version);
    }

    /** 정보를 고칠 수 있는지(판매자 본인, 삭제 전) 검사한다. 가격 인상 제한을 확인하기 전에 쓴다 */
    public void checkEditableBy(Long sellerUserId) {
        validateEditableBy(sellerUserId);
    }

    /** 새 가격이 지금 가격보다 큰지. 올릴 때만 가격 인상 제한을 확인한다 */
    public boolean isPriceRaise(long newPriceHp) {
        return newPriceHp > priceHp;
    }

    /**
     * 가격 인상 제한을 확인할 때 읽은 가격({@code expectedPriceHp})이 그대로일 때만 제목·소개·가격·썸네일을 바꾼다.
     * 그 사이 가격이 바뀌었으면 확인이 맞지 않으므로 거부한다.
     */
    public void updateInfo(Long sellerUserId, long expectedPriceHp, String title, String description, long priceHp,
                           Long thumbnailFileId, LocalDateTime now) {
        validateEditableBy(sellerUserId);
        if (this.priceHp != expectedPriceHp) {
            throw new LectureException(LectureErrorCode.LECTURE_PRICE_CHANGED);
        }
        updateInfo(sellerUserId, title, description, priceHp, thumbnailFileId, now);
    }

    /**
     * 제목·소개·가격·썸네일을 바꾼다. 재검수 대상이 아니므로 상태는 그대로다.
     */
    public void updateInfo(
            Long sellerUserId,
            String title,
            String description,
            long priceHp,
            Long thumbnailFileId,
            LocalDateTime now
    ) {
        validateEditableBy(sellerUserId);
        validateInfo(title, priceHp);
        this.title = title;
        this.description = description;
        this.priceHp = priceHp;
        this.thumbnailFileId = thumbnailFileId;
        this.updatedAt = now;
    }

    /**
     * 반려된 강의의 콘텐츠 목록 전체를 바꾼다. 상태는 반려 그대로이고, 재검수는 {@link #requestInspection}으로 따로 신청한다.
     * 순서는 매번 1부터 다시 매기므로 겹치지 않는다. 동시 교체는 {@code updatedAt} 변경에 따른 {@code @Version} 검사로 막는다.
     */
    public void replaceContents(Long sellerUserId, List<LectureContentSpec> contentSpecs, LocalDateTime now) {
        validateEditableBy(sellerUserId);
        if (status != LectureStatus.REJECTED) {
            throw new LectureException(LectureErrorCode.CONTENT_NOT_EDITABLE);
        }
        List<LectureContent> newContents = createContents(contentSpecs, now);
        contents.forEach(content -> content.markDeleted(now));
        contents.clear();
        contents.addAll(newContents);
        this.updatedAt = now;
    }

    public void approve(Long adminUserId, LocalDateTime now) {
        validateInspectable(adminUserId);
        this.status = LectureStatus.ON_SALE;
        this.inspectedAt = now;
        this.inspectedBy = adminUserId;
        this.updatedAt = now;
    }

    public void reject(Long adminUserId, String reason, LocalDateTime now) {
        validateInspectable(adminUserId);
        if (reason == null || reason.isBlank()) {
            throw new LectureException(LectureErrorCode.REJECTION_REASON_REQUIRED);
        }
        this.status = LectureStatus.REJECTED;
        this.rejectionReason = reason;
        this.inspectedAt = now;
        this.inspectedBy = adminUserId;
        this.updatedAt = now;
    }

    /**
     * 반려된 강의를 다시 검수 대기로 올린다. 이전 반려 사유는 다음 반려 때 갱신한다. 검수 회차가 1 오른다.
     */
    public void requestInspection(Long sellerUserId, LocalDateTime now) {
        validateEditableBy(sellerUserId);
        if (status != LectureStatus.REJECTED) {
            throw new LectureException(LectureErrorCode.NOT_REJECTED);
        }
        this.status = LectureStatus.PENDING_INSPECTION;
        this.inspectionRequestedAt = now;
        this.inspectionCount++;
        this.updatedAt = now;
    }

    /**
     * 목록과 신규 판매에서 빠진다. 기존 구매자는 계속 볼 수 있다.
     */
    public void delete(Long sellerUserId, LocalDateTime now) {
        validateSeller(sellerUserId);
        if (isDeleted()) {
            return;
        }
        this.deletedAt = now;
        this.updatedAt = now;
    }

    /**
     * 콘텐츠(영상·자료)를 볼 수 있는 회원인지 확인한다 (D26, D38).
     * <ul>
     *     <li>판매자 본인과 관리자는 상태와 관계없이(삭제 포함) 볼 수 있다.</li>
     *     <li>이 강의를 시청할 수 있는 구매자는 판매자가 삭제한 뒤에도 볼 수 있다(D5). 결제 처리 중은 아니다(D69). 다만 구매 확정 전이면 재생 동의가 필요하다(D7·D8).</li>
     *     <li>그 밖의 회원에게 판매 중이 아닌 강의는 상세 조회와 같이 없는 강의({@code LECTURE_NOT_FOUND})로 보이고,
     *     판매 중인 강의는 {@code CONTENT_ACCESS_DENIED}다.</li>
     * </ul>
     *
     * @param heldItem 회원이 이 강의를 시청할 수 있는 주문 항목. 없으면 {@code null}
     */
    public void validateContentViewer(Long userId, boolean admin, OrderItem heldItem) {
        if (isSeller(userId) || admin) {
            return;
        }
        if (heldItem != null && heldItem.isViewable()) {
            if (heldItem.requiresPlayConsent()) {
                throw new PlayConsentRequiredException(heldItem);
            }
            return;
        }
        if (!isOnSale()) {
            throw new LectureException(LectureErrorCode.LECTURE_NOT_FOUND);
        }
        throw new LectureException(LectureErrorCode.CONTENT_ACCESS_DENIED);
    }

    /**
     * 목록 순서대로 1부터 순서를 매긴다. 하나라도 잘못되면 아무것도 만들지 않는다.
     */
    private List<LectureContent> createContents(List<LectureContentSpec> contentSpecs, LocalDateTime now) {
        if (contentSpecs == null || contentSpecs.isEmpty()) {
            throw new LectureException(LectureErrorCode.CONTENT_REQUIRED);
        }
        List<LectureContent> created = new ArrayList<>();
        for (int i = 0; i < contentSpecs.size(); i++) {
            created.add(new LectureContent(this, contentSpecs.get(i), i + 1, now));
        }
        return created;
    }

    private void validateEditableBy(Long sellerUserId) {
        validateSeller(sellerUserId);
        validateNotDeleted();
    }

    private void validateSeller(Long sellerUserId) {
        if (!this.sellerUserId.equals(sellerUserId)) {
            throw new LectureException(LectureErrorCode.NOT_LECTURE_SELLER);
        }
    }

    private void validateNotDeleted() {
        if (isDeleted()) {
            throw new LectureException(LectureErrorCode.LECTURE_NOT_FOUND);
        }
    }

    private void validateInspectable(Long adminUserId) {
        if (adminUserId == null) {
            throw new LectureException(LectureErrorCode.INVALID_REQUEST);
        }
        validateNotDeleted();
        if (status != LectureStatus.PENDING_INSPECTION) {
            throw new LectureException(LectureErrorCode.NOT_PENDING_INSPECTION);
        }
    }

    private static void validateInfo(String title, long priceHp) {
        if (title == null || title.isBlank()) {
            throw new LectureException(LectureErrorCode.INVALID_REQUEST);
        }
        if (priceHp < PRICE_UNIT_HP || priceHp % PRICE_UNIT_HP != 0) {
            throw new LectureException(LectureErrorCode.INVALID_PRICE);
        }
    }
}
