package com.hapbang.customersupport.domain;

import java.time.LocalDateTime;

import org.springframework.util.Assert;

import com.hapbang.shared.jpa.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 문의에 대한 관리자 답변. 문의 애그리게이트의 일부라 {@link Inquiry}를 통해서만 만들고, 고치고, 지운다.
 * 답변한 관리자는 회원 ID로만 참조한다. 생성일·수정일은 {@link BaseEntity}가 기록한다.
 */
@Entity
@Table(name = "inquiry_reply", schema = "customersupport")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InquiryReply extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inquiry_id", nullable = false)
    private Inquiry inquiry;

    @Column(nullable = false)
    private Long adminUserId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    private LocalDateTime deletedAt;

    private InquiryReply(Inquiry inquiry, Long adminUserId, String content) {
        this.inquiry = inquiry;
        this.adminUserId = adminUserId;
        this.content = content;
    }

    /** {@link Inquiry#reply}만 부른다 */
    static InquiryReply create(Inquiry inquiry, Long adminUserId, String content) {
        Assert.state(adminUserId != null, "답변한 관리자가 없습니다.");
        Assert.state(content != null && !content.isBlank(), "답변 내용이 비어 있습니다.");
        return new InquiryReply(inquiry, adminUserId, content.strip());
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** {@link Inquiry#editReply}만 부른다 */
    void edit(String content) {
        Assert.state(!isDeleted(), "삭제된 답변은 고칠 수 없습니다.");
        Assert.state(content != null && !content.isBlank(), "답변 내용이 비어 있습니다.");
        this.content = content.strip();
    }

    /** {@link Inquiry#deleteReply}만 부른다 */
    void delete(LocalDateTime now) {
        Assert.state(!isDeleted(), "이미 삭제된 답변입니다.");
        this.deletedAt = now;
    }
}
