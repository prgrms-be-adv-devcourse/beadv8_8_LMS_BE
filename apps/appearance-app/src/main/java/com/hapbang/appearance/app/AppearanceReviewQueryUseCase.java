package com.hapbang.appearance.app;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceReview;
import com.hapbang.shared.appearance.dto.AppearanceParticipantRole;
import com.hapbang.shared.appearance.dto.AppearanceReceivedReviewDto;

import lombok.RequiredArgsConstructor;

/**
 * 회원이 받은 공개 리뷰 조회 (#514). 회원 모듈이 프로필의 리뷰 목록을 만들 때 내부 API로 부른다.
 * 트랜잭션은 호출한 {@link AppearanceReviewInternalFacade}가 연다 (읽기 전용). 응답은 shared 계약 DTO다.
 * <ul>
 *     <li>공개된 리뷰만 준다. 블라인드 공개 전(상대가 아직 안 씀) 리뷰는 받은 사람에게도 보이지 않으므로 뺀다.</li>
 *     <li>최신 공개순(공개 시각 desc, 같으면 리뷰 ID desc)으로 고정한다. 함께 공개된 두 리뷰는 받은 사람이 달라 한 목록에 같이 나오지 않는다.</li>
 *     <li>출연 쪽에 리뷰가 없는 회원은 빈 페이지다 (회원 복제를 확인하지 않는다: 동기화가 늦어도 회원 화면이 깨지지 않게, 수익 내부 조회와 같음).
 *     쓴 사람이 그 뒤 탈퇴·정지돼도 이미 공개된 리뷰는 그대로 준다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AppearanceReviewQueryUseCase {

    private final AppearanceReviewSupport appearanceReviewSupport;

    /**
     * @throws AppearanceException 회원 ID가 없거나 1 미만 ({@code INVALID_REQUEST})
     */
    public Page<AppearanceReceivedReviewDto> findReceived(Long userId, Pageable pageable) {
        if (userId == null || userId < 1) {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST, "회원 ID는 1 이상이어야 합니다.");
        }
        Page<AppearanceReview> reviews = appearanceReviewSupport.findPublishedByReviewee(userId, pageable);
        List<AppearanceReceivedReviewDto> items = new ArrayList<>();
        for (AppearanceReview review : reviews.getContent()) {
            items.add(new AppearanceReceivedReviewDto(review.getId(), review.getAppearanceId(), review.getReviewerId(),
                    AppearanceParticipantRole.valueOf(review.getRevieweeRole().name()), review.getRating(),
                    review.getContent(), review.getPublishedAt()));
        }
        return new PageImpl<>(items, reviews.getPageable(), reviews.getTotalElements());
    }
}
