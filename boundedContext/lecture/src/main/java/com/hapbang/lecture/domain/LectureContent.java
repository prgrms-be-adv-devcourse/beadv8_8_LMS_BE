package com.hapbang.lecture.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 강의 영상·자료 한 개. {@link Lecture}를 통해서만 만들고 지운다.
 */
@Entity
@Table(name = "lecture_content", schema = "lectures",
        indexes = @Index(name = "idx_lecture_content_lecture_id", columnList = "lecture_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LectureContent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lecture_id", nullable = false)
    private Lecture lecture;

    @Column(nullable = false)
    private Long fileId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private int sequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentType contentType;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    LectureContent(Lecture lecture, LectureContentSpec spec, int sequence, LocalDateTime now) {
        if (spec == null || spec.fileId() == null || spec.title() == null || spec.title().isBlank()
                || spec.contentType() == null) {
            throw new LectureException(LectureErrorCode.INVALID_REQUEST);
        }
        this.lecture = lecture;
        this.fileId = spec.fileId();
        this.title = spec.title();
        this.sequence = sequence;
        this.contentType = spec.contentType();
        this.createdAt = now;
        this.updatedAt = now;
    }

    void markDeleted(LocalDateTime now) {
        this.deletedAt = now;
        this.updatedAt = now;
    }
}
