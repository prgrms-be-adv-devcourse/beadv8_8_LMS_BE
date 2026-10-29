package com.hapbang.lecture.domain;

import java.time.LocalDateTime;
import java.util.UUID;

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
 * HP 작업 시도 한 번 = 선점 한 번 (D57 (3)). 선점할 때 만들고 결과를 한 번만 기록한 뒤 바꾸지 않는다. 삭제하지 않는다.
 * 원래 오류 코드·진단 정보는 관리자만 본다.
 */
@Entity
@Table(name = "lecture_hp_task_attempt", schema = "lectures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LectureHpTaskAttempt {

    /** 진단 정보 최대 길이 (컬럼 길이와 같다) */
    static final int ERROR_DETAIL_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 같은 스키마의 작업이지만 연관관계 없이 ID로만 참조한다(시도는 작업을 바꾸지 않는다)
    @Column(nullable = false)
    private Long taskId;

    /** 작업의 누적 선점 횟수 */
    @Column(nullable = false)
    private int attemptNo;

    @Column(nullable = false)
    private UUID claimToken;

    @Enumerated(EnumType.STRING)
    private LectureHpAttemptResult result;

    @Column(length = 100)
    private String errorCode;

    @Column(length = 100)
    private String rawErrorCode;

    @Column(length = ERROR_DETAIL_MAX_LENGTH)
    private String errorDetail;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LectureHpTaskAttempt(Long taskId, int attemptNo, UUID claimToken, LocalDateTime now) {
        this.taskId = taskId;
        this.attemptNo = attemptNo;
        this.claimToken = claimToken;
        this.startedAt = now;
    }

    public static LectureHpTaskAttempt start(Long taskId, int attemptNo, UUID claimToken, LocalDateTime now) {
        return new LectureHpTaskAttempt(taskId, attemptNo, claimToken, now);
    }

    public boolean isOpen() {
        return result == null;
    }

    /** 결과를 한 번만 기록한다. 이미 닫힌 시도(선점 만료 등)는 바꾸지 않는다 */
    public void finish(LectureHpAttemptResult result, String errorCode, String rawErrorCode, String errorDetail,
                       LocalDateTime now) {
        if (!isOpen()) {
            return;
        }
        this.result = result;
        this.errorCode = errorCode;
        this.rawErrorCode = rawErrorCode;
        this.errorDetail = errorDetail == null || errorDetail.length() <= ERROR_DETAIL_MAX_LENGTH ? errorDetail
                : errorDetail.substring(0, ERROR_DETAIL_MAX_LENGTH);
        this.completedAt = now;
    }
}
