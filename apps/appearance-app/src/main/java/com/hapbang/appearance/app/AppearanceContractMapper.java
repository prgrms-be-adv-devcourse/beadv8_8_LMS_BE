package com.hapbang.appearance.app;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceApplication;
import com.hapbang.appearance.domain.AppearanceCancelRequest;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceReview;
import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.shared.appearance.domain.AppearanceResult;
import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceCancelRequestDto;
import com.hapbang.shared.appearance.dto.AppearanceParticipantRole;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;
import com.hapbang.shared.appearance.event.AppearanceChangedEvent;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;

/**
 * 출연 도메인을 다른 앱에 보내는 {@code shared} DTO·이벤트로 바꾼다 (#825). 도메인 엔티티는 {@code shared} 계약을 모르고,
 * 의존 방향은 app → domain, app → shared다. 응답 DTO의 {@code ResDto.from(엔티티)}와 같은 자리(app 계층)에 둔다.
 * <p>
 * 이벤트·동기화 내부 API·알림 이벤트가 모두 이 변환을 쓴다. 값은 옮기기 전 도메인 메서드와 같다.
 */
public final class AppearanceContractMapper {

    private AppearanceContractMapper() {
    }

    /**
     * 다른 모듈에 알리는 출연 동기화용 사본 (#713). 출연 변경 이벤트와 동기화 내부 API가 같이 쓴다.
     * 방송 예정 일시는 DB 컬럼(TIMESTAMP(6))과 같은 마이크로초까지만 둔다. 이벤트 값이 저장된 값(내부 API)과 같아야 하기 때문이다.
     *
     * @throws IllegalStateException 아직 DB에 반영되지 않은 출연. {@code @Version}은 flush 때 오르므로 반드시 flush 뒤에 만든다
     */
    public static AppearanceSyncDto toSyncDto(Appearance appearance) {
        if (appearance.getId() == null || appearance.getVersion() == null) {
            throw new IllegalStateException("DB에 반영된 출연만 동기화 사본으로 만들 수 있습니다: " + appearance.getId());
        }
        return new AppearanceSyncDto(appearance.getId(), appearance.getHostId(), appearance.getGuestId(),
                toResult(appearance.getStatus()), appearance.getScheduledAt().truncatedTo(ChronoUnit.MICROS),
                appearance.getVersion());
    }

    /**
     * 출연 변경 이벤트 (#713). 상태를 바꾼 뒤 flush하고 만든다. flush 전에 만들면 이전 version이 실려
     * 받는 쪽이 오래된 이벤트로 버린다.
     */
    public static AppearanceChangedEvent toChangedEvent(Appearance appearance) {
        return new AppearanceChangedEvent(toSyncDto(appearance));
    }

    /**
     * 다른 모듈에 알리는 공개 결과 (#713). 상태를 더하면 여기서 컴파일 오류가 나므로 공개 결과를 꼭 정한다.
     * 취소 요청 중은 아직 성립한 출연이라 {@link AppearanceResult#SCHEDULED}다.
     */
    public static AppearanceResult toResult(AppearanceStatus status) {
        return switch (status) {
            case ESTABLISHED, CANCEL_REQUESTED -> AppearanceResult.SCHEDULED;
            case CONFIRMED -> AppearanceResult.COMPLETED;
            case CANCELED_BY_HOST, CANCELED_BY_AGREEMENT -> AppearanceResult.CANCELED;
            case ABSENT -> AppearanceResult.NO_SHOW;
        };
    }

    /**
     * 다른 모듈에 알리는 리뷰 공개 이벤트 (#513). 본문은 담지 않는다.
     *
     * @throws IllegalStateException 아직 공개되지 않은 리뷰
     */
    public static AppearanceReviewPublishedEvent toPublishedEvent(AppearanceReview review) {
        if (!review.isPublished()) {
            throw new IllegalStateException("공개된 리뷰만 공개 이벤트로 알릴 수 있습니다: " + review.getId());
        }
        return new AppearanceReviewPublishedEvent(review.getId(), review.getAppearanceId(), review.getReviewerId(),
                review.getRevieweeId(), AppearanceParticipantRole.valueOf(review.getRevieweeRole().name()),
                review.getRating(), review.getPublishedAt());
    }

    /**
     * 다른 모듈에 알리는 리뷰 동기화용 사본 (#713). 공개 이벤트({@link #toPublishedEvent})의 같은 이름 필드와 값이 같다.
     * 리뷰는 공개 뒤 바뀌지 않으므로 version을 담지 않는다.
     *
     * @throws IllegalStateException 아직 공개되지 않은 리뷰
     */
    public static AppearanceReviewSyncDto toSyncDto(AppearanceReview review) {
        if (!review.isPublished()) {
            throw new IllegalStateException("공개된 리뷰만 동기화 사본으로 만들 수 있습니다: " + review.getId());
        }
        return new AppearanceReviewSyncDto(review.getId(), review.getAppearanceId(), review.getRevieweeId(),
                review.getRating());
    }

    /** 다른 모듈에 넘기는 요청서 정보 (요청 도착 이벤트) */
    public static AppearanceRequestDto toDto(AppearanceRequest request) {
        return new AppearanceRequestDto(request.getId(), request.getHostId(), request.getGuestId(), request.getTitle(),
                request.getFee(), request.getScheduledAt(), request.getAcceptDeadline());
    }

    /**
     * 다른 모듈에 넘기는 지원 정보 (지원 도착 이벤트). 지원 메시지는 담지 않는다.
     *
     * @param recruit 이 지원의 모집글 (제목·호스트를 함께 담는다)
     * @throws IllegalArgumentException 이 지원의 모집글이 아님
     */
    public static AppearanceApplicationDto toDto(AppearanceApplication application, AppearanceRecruit recruit) {
        if (!Objects.equals(recruit.getId(), application.getRecruitId())) {
            throw new IllegalArgumentException("지원한 모집글이 아닙니다: recruitId=%d, 받은 모집글=%d"
                    .formatted(application.getRecruitId(), recruit.getId()));
        }
        return new AppearanceApplicationDto(application.getId(), application.getRecruitId(), recruit.getTitle(),
                recruit.getHostId(), application.getGuestId(), application.getCreatedAt());
    }

    /**
     * 다른 모듈에 넘기는 취소 요청 정보 (알림 이벤트). 취소·거절 사유는 담지 않는다.
     *
     * @param title       출처 요청서·모집글의 제목
     * @param scheduledAt 출연 방송 예정 일시
     */
    public static AppearanceCancelRequestDto toDto(AppearanceCancelRequest cancelRequest, String title,
                                                   LocalDateTime scheduledAt) {
        return new AppearanceCancelRequestDto(cancelRequest.getId(), cancelRequest.getAppearanceId(), title,
                cancelRequest.getHostId(), cancelRequest.getGuestId(), cancelRequest.getSequence(), scheduledAt);
    }
}
