package com.hapbang.member.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 출연 리뷰 공개 이벤트 반영 (#555). Kafka 리스너가 부른다. 로그인 사용자가 없는 소비 스레드라 권한 검사는 두지 않는다
 */
@Service
@RequiredArgsConstructor
public class MemberAppearanceRatingFacade {

    private final MemberAppearanceRatingUseCase memberAppearanceRatingUseCase;

    /**
     * 반영 기록과 평점 합계를 한 트랜잭션으로 저장한다. 리스너는 이 커밋이 끝난 뒤 반환하므로 offset은 언제나 DB 커밋 뒤에 커밋된다
     *
     * @return 이번에 반영했으면 true, 이미 반영한 리뷰면 false
     */
    @Transactional
    public boolean applyReviewPublished(AppearanceReviewPublishedEvent event) {
        return memberAppearanceRatingUseCase.apply(event);
    }
}
