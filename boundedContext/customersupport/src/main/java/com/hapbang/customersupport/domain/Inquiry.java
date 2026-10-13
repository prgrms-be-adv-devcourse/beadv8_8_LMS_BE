package com.hapbang.customersupport.domain;

import java.time.LocalDateTime;

import org.springframework.util.Assert;

import com.hapbang.shared.jpa.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원의 1:1 문의. 작성자는 회원 ID로만 참조한다. 생성일·수정일은 {@link BaseEntity}가 기록한다.
 * 문의 본문은 분쟁 확인용으로 남기므로 Hard Delete 하지 않는다. 삭제(Soft Delete)는 답변 대기일 때만 된다.
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

    private LocalDateTime deletedAt;

    private Inquiry(Long userId, InquiryCategory category, String title, String content) {
        this.userId = userId;
        this.category = category;
        this.title = title;
        this.content = content;
        this.status = InquiryStatus.WAITING;
    }

    public static Inquiry create(Long userId, InquiryCategory category, String title, String content) {
        Assert.state(userId != null, "작성자가 없습니다.");
        Assert.state(category != null, "문의 유형이 없습니다.");
        Assert.state(hasText(title) && title.strip().length() <= TITLE_MAX_LENGTH, "문의 제목이 올바르지 않습니다.");
        Assert.state(hasText(content), "문의 내용이 비어 있습니다.");
        return new Inquiry(userId, category, title.strip(), content.strip());
    }

    public boolean isWrittenBy(Long userId) {
        return this.userId.equals(userId);
    }

    /** 답변 대기 중인지. 답변이 달리기 전에만 삭제할 수 있다 */
    public boolean isWaiting() {
        return status == InquiryStatus.WAITING;
    }

    /** Soft Delete. 본문은 분쟁 확인용으로 남는다 */
    public void delete(LocalDateTime now) {
        Assert.state(isWaiting(), "답변이 달린 문의는 삭제할 수 없습니다.");
        Assert.state(deletedAt == null, "이미 삭제된 문의입니다.");
        this.deletedAt = now;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
