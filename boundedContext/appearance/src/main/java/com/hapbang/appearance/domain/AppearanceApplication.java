package com.hapbang.appearance.domain;

import java.time.LocalDateTime;
import java.util.Objects;

import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.jpa.BaseEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 출연 모집 지원. 게스트 한 명이 모집글 하나에 낸 지원이다.
 * <ul>
 *     <li>한 모집글에 게스트 1명당 1건이다(DB 유니크). 지원을 취소했거나 거절돼도 다시 지원할 수 없다 (AGENTS.md 5장).</li>
 *     <li>호스트가 수락하면 출연({@link Appearance})이 성립한다. 모집글 쪽 확인(호스트·모집 중·정원)은 {@link AppearanceRecruit}가 한다.</li>
 *     <li>지원할 수 있는지(모집 중·마감 전·자기 모집글 아님)는 모집글이 확인한다 ({@link AppearanceRecruit#validateApplicable}).</li>
 * </ul>
 */
@Entity
@Table(name = "applications", schema = "appearances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppearanceApplication extends BaseEntity {

    /** 지원 메시지 최대 길이 (DB 컬럼 VARCHAR(500)) */
    public static final int MESSAGE_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 지원한 모집글 ID */
    private Long recruitId;

    /** 지원한 게스트 회원 ID */
    private Long guestId;

    /** 지원 메시지 (선택, 500자 이하) */
    private String message;

    @Enumerated(EnumType.STRING)
    private AppearanceApplicationStatus status;

    /** 호스트 응답(수락·거절) 시각. 모집 마감으로 미선정되면 비어 있다 (모집글의 마감 시각을 본다) */
    private LocalDateTime respondedAt;

    /** 게스트 지원 취소 시각 */
    private LocalDateTime withdrawnAt;

    /** 낙관적 락. 수락·거절·취소가 동시에 들어오면 나중 것을 막는다 */
    @Version
    private Long version;

    private AppearanceApplication(Long recruitId, Long guestId, String message) {
        this.recruitId = recruitId;
        this.guestId = guestId;
        this.message = message;
        this.status = AppearanceApplicationStatus.APPLIED;
    }

    /**
     * 모집글에 지원한다.
     *
     * @throws AppearanceException 자기 모집글({@code CANNOT_APPLY_OWN_RECRUIT}), 모집 중이 아니거나 마감 일시가 지남
     *                             ({@code APPEARANCE_RECRUIT_NOT_OPEN}), 메시지 501자 이상({@code INVALID_REQUEST})
     */
    public static AppearanceApplication apply(AppearanceRecruit recruit, Long guestId, String message,
                                              LocalDateTime now) {
        recruit.validateApplicable(guestId, now);
        if (message != null && message.length() > MESSAGE_MAX) {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST,
                    "지원 메시지는 %d자 이하여야 합니다.".formatted(MESSAGE_MAX));
        }
        return new AppearanceApplication(recruit.getId(), guestId, message);
    }

    /**
     * 게스트가 지원을 취소한다. 호스트가 응답하기 전까지만 할 수 있다.
     *
     * @throws AppearanceException 지원한 게스트가 아님({@code NOT_APPLICATION_GUEST}), 대기 중이 아님({@code APPLICATION_NOT_PENDING})
     */
    public void withdraw(Long userId, LocalDateTime now) {
        if (!isGuest(userId)) {
            throw new AppearanceException(AppearanceErrorCode.NOT_APPLICATION_GUEST);
        }
        requirePending();
        this.status = AppearanceApplicationStatus.WITHDRAWN;
        this.withdrawnAt = now;
    }

    /**
     * 호스트가 수락한다. 호스트·모집 상태·정원은 호출한 쪽이 모집글로 먼저 확인한다.
     *
     * @throws AppearanceException 대기 중이 아님({@code APPLICATION_NOT_PENDING})
     */
    public void select(LocalDateTime now) {
        requirePending();
        this.status = AppearanceApplicationStatus.SELECTED;
        this.respondedAt = now;
    }

    /**
     * 호스트가 거절한다 (사유 없음). 호스트·모집 상태는 호출한 쪽이 모집글로 먼저 확인한다.
     *
     * @throws AppearanceException 대기 중이 아님({@code APPLICATION_NOT_PENDING})
     */
    public void reject(LocalDateTime now) {
        requirePending();
        this.status = AppearanceApplicationStatus.NOT_SELECTED;
        this.respondedAt = now;
    }

    /**
     * 호스트가 응답하지 않은 채 모집이 마감돼 미선정으로 정리한다. 호스트가 거절한 것이 아니라서 응답 시각은 남기지 않는다.
     *
     * @throws AppearanceException 대기 중이 아님({@code APPLICATION_NOT_PENDING})
     */
    public void closeUnselected() {
        requirePending();
        this.status = AppearanceApplicationStatus.NOT_SELECTED;
    }

    /**
     * 다른 모듈에 넘기는 지원 정보 (지원 도착 이벤트). 지원 메시지는 담지 않는다.
     *
     * @param recruit 이 지원의 모집글 (제목·호스트를 함께 담는다)
     */
    public AppearanceApplicationDto toDto(AppearanceRecruit recruit) {
        if (!Objects.equals(recruit.getId(), recruitId)) {
            throw new IllegalArgumentException("지원한 모집글이 아닙니다: recruitId=%d, 받은 모집글=%d"
                    .formatted(recruitId, recruit.getId()));
        }
        return new AppearanceApplicationDto(id, recruitId, recruit.getTitle(), recruit.getHostId(), guestId,
                getCreatedAt());
    }

    public boolean isGuest(Long userId) {
        return guestId.equals(userId);
    }

    private void requirePending() {
        if (status != AppearanceApplicationStatus.APPLIED) {
            throw new AppearanceException(AppearanceErrorCode.APPLICATION_NOT_PENDING);
        }
    }
}
