package com.hapbang.appearance.app;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceReview;
import com.hapbang.appearance.out.AppearanceRepository;
import com.hapbang.appearance.out.AppearanceReviewRepository;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncDto;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncPageDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncPageDto;

import lombok.RequiredArgsConstructor;

/**
 * 출연·공개 리뷰 동기화 목록 (#713). ID 오름차순 키셋으로 읽는다. 트랜잭션은 호출한 쪽이 연다.
 * <ul>
 *     <li>size+1개를 읽어 다음 페이지가 있는지 판단한다. 다음 페이지가 없으면 {@code nextAfterId}는 null이다.</li>
 *     <li>항목은 이벤트와 같은 DTO 생성 메서드로 만들어, 같은 출연·같은 version이면 값이 같다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AppearanceSyncQueryUseCase {

    private final AppearanceRepository appearanceRepository;
    private final AppearanceReviewRepository appearanceReviewRepository;

    /** 모든 출연 (출연은 지우지 않는다) */
    public AppearanceSyncPageDto findAppearances(Long afterId, int size) {
        List<Appearance> found = appearanceRepository.findByIdGreaterThanOrderByIdAsc(start(afterId),
                Limit.of(size + 1));
        boolean hasNext = found.size() > size;
        List<Appearance> page = hasNext ? found.subList(0, size) : found;
        List<AppearanceSyncDto> items = new ArrayList<>();
        for (Appearance appearance : page) {
            items.add(AppearanceContractMapper.toAppearanceSyncDto(appearance));
        }
        return new AppearanceSyncPageDto(items, hasNext ? page.getLast().getId() : null);
    }

    /** 공개된 리뷰만. 공개 전 리뷰는 DB에서 걸러 키셋을 잇는다 */
    public AppearanceReviewSyncPageDto findPublishedReviews(Long afterId, int size) {
        List<AppearanceReview> found = appearanceReviewRepository
                .findByIdGreaterThanAndPublishedAtIsNotNullOrderByIdAsc(start(afterId), Limit.of(size + 1));
        boolean hasNext = found.size() > size;
        List<AppearanceReview> page = hasNext ? found.subList(0, size) : found;
        List<AppearanceReviewSyncDto> items = new ArrayList<>();
        for (AppearanceReview review : page) {
            items.add(AppearanceContractMapper.toReviewSyncDto(review));
        }
        return new AppearanceReviewSyncPageDto(items, hasNext ? page.getLast().getId() : null);
    }

    private static long start(Long afterId) {
        return afterId == null ? 0L : afterId;
    }
}
