package com.hapbang.member.app;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.appearance.dto.AppearanceReceivedReviewDto;
import com.hapbang.shared.appearance.dto.AppearanceReceivedReviewPageDto;
import com.hapbang.shared.appearance.out.AppearanceApiCallException;
import com.hapbang.shared.appearance.out.AppearanceApiClient;

import lombok.extern.slf4j.Slf4j;

/**
 * 회원이 받은 출연 리뷰 목록 (#555). 리뷰는 회원 DB에 복사하지 않고 출연 내부 API(shared {@link AppearanceApiClient})로
 * 그때그때 읽고, 쓴 회원은 한 페이지분을 회원 DB에서 한 번에 읽어 붙인다.
 * <p>
 * 동기 HTTP 호출이라 트랜잭션 안에서 부르지 않는다(AGENTS.md 3장). Client가 없으면(출연 주소 미설정) 읽을 수 없는 것으로 본다
 */
@Slf4j
@Service
public class MemberAppearanceReviewUseCase {

    private final ObjectProvider<AppearanceApiClient> appearanceApiClient;
    private final UserRepository userRepository;

    public MemberAppearanceReviewUseCase(ObjectProvider<AppearanceApiClient> appearanceApiClient,
                                         UserRepository userRepository) {
        this.appearanceApiClient = appearanceApiClient;
        this.userRepository = userRepository;
    }

    /** 최신 공개순. 출연 쪽에 리뷰가 없으면 빈 페이지 */
    public Page<ReceivedAppearanceReview> findReceived(Long userId, int page, int size) {
        AppearanceReceivedReviewPageDto received = call(userId, page, size);
        List<Long> reviewerIds = received.content().stream()
                .map(AppearanceReceivedReviewDto::reviewerUserId)
                .distinct()
                .toList();
        Map<Long, User> reviewers = userRepository.findAllById(reviewerIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        List<ReceivedAppearanceReview> content = received.content().stream()
                .map(review -> new ReceivedAppearanceReview(review, reviewers.get(review.reviewerUserId())))
                .toList();
        AppearanceReceivedReviewPageDto.PageMetadata metadata = received.page();
        return new PageImpl<>(content, PageRequest.of((int) metadata.number(), (int) metadata.size()),
                metadata.totalElements());
    }

    // 4xx(REJECTED)는 내부 API 키·계약 불일치라 다시 시도해도 같고, 나머지는 일시 장애다. 사용자에게는 둘 다 지금 읽을 수 없다고 답한다
    private AppearanceReceivedReviewPageDto call(Long userId, int page, int size) {
        AppearanceApiClient client = appearanceApiClient.getIfAvailable();
        if (client == null) {
            log.error("출연 내부 API Client가 없어 받은 출연 리뷰를 읽을 수 없습니다(hapbang.clients.appearance.base-url 미설정).");
            throw new MemberException(MemberErrorCode.APPEARANCE_REVIEW_UNAVAILABLE);
        }
        try {
            return client.getReceivedReviews(userId, page, size);
        } catch (AppearanceApiCallException e) {
            if (e.getReason() == AppearanceApiCallException.Reason.REJECTED) {
                log.error("출연 내부 API가 받은 리뷰 조회를 거절했습니다. status={}, code={}", e.getStatus(), e.getCode(), e);
            } else {
                log.warn("출연 내부 API로 받은 리뷰를 읽지 못했습니다. status={}", e.getStatus(), e);
            }
            throw new MemberException(MemberErrorCode.APPEARANCE_REVIEW_UNAVAILABLE, e);
        }
    }
}
