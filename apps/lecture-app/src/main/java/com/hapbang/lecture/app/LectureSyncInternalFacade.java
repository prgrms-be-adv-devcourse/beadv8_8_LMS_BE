package com.hapbang.lecture.app;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.springframework.data.domain.Limit;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.domain.CartItem;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.out.CartRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.out.LectureReviewRepository;
import com.hapbang.lecture.out.ReviewWithItemStatus;
import com.hapbang.shared.lecture.dto.CartItemSyncPageDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureSyncPageDto;

import lombok.RequiredArgsConstructor;

/**
 * AI 추천용 동기화 내부 API (#712, D82, docs/api/internal-lecture-sync.md). 종류마다 ID 오름차순 키셋 페이지를 준다.
 * <ul>
 *     <li>항목은 이벤트와 같은 엔티티의 {@code toSyncDto}로 만들어, 같은 ID·같은 버전이면 이벤트와 같은 값이다.</li>
 *     <li>Soft Delete된 행도 {@code deleted=true}로 싣는다. size+1개를 읽어 다음 페이지가 있는지 판단한다(회원 내부 API와 같음).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
// 내부 API 보안 체인(global InternalApiSecurityConfig)이 키를 검사하고, 회원 로그인이 이 진입점에 오지 못하게 한 번 더 막는다
@PreAuthorize("hasRole('INTERNAL')")
public class LectureSyncInternalFacade {

    private final LectureRepository lectureRepository;
    private final LectureOrderRepository lectureOrderRepository;
    private final CartRepository cartRepository;
    private final LectureReviewRepository lectureReviewRepository;

    /**
     * 강의 행과 콘텐츠를 다른 쿼리로 읽으므로, 사이에 콘텐츠 교체가 커밋되면 옛 version에 새 콘텐츠가 실릴 수 있다.
     * 트랜잭션 하나가 한 스냅숏을 보게 REPEATABLE READ로 읽는다(PostgreSQL에서 읽기 전용이면 직렬화 실패가 없다).
     * 다른 종류는 한 쿼리(fetch join·projection)로 읽어 쿼리 하나의 스냅숏으로 충분하다
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LectureSyncPageDto getLectures(Long afterId, int size) {
        KeysetPage<Lecture> page = KeysetPage.of(lectureRepository::findByIdGreaterThanOrderByIdAsc, afterId, size);
        return new LectureSyncPageDto(page.map(Lecture::toSyncDto), page.nextAfterId(Lecture::getId));
    }

    @Transactional(readOnly = true)
    public LecturePurchaseSyncPageDto getPurchases(Long afterId, int size) {
        KeysetPage<OrderItem> page = KeysetPage.of(lectureOrderRepository::findSyncPurchases, afterId, size);
        return new LecturePurchaseSyncPageDto(page.map(OrderItem::toSyncDto), page.nextAfterId(OrderItem::getId));
    }

    @Transactional(readOnly = true)
    public CartItemSyncPageDto getCartItems(Long afterId, int size) {
        KeysetPage<CartItem> page = KeysetPage.of(cartRepository::findSyncCartItems, afterId, size);
        return new CartItemSyncPageDto(page.map(CartItem::toSyncDto), page.nextAfterId(CartItem::getId));
    }

    @Transactional(readOnly = true)
    public LectureReviewSyncPageDto getReviews(Long afterId, int size) {
        KeysetPage<ReviewWithItemStatus> page = KeysetPage.of(lectureReviewRepository::findSyncReviews, afterId, size);
        return new LectureReviewSyncPageDto(page.map(row -> row.review().toSyncDto(row.itemStatus())),
                page.nextAfterId(row -> row.review().getId()));
    }

    /** 키셋 한 페이지. size+1개를 읽어 다음 페이지가 있으면 마지막 하나를 버린다 */
    private record KeysetPage<T>(List<T> rows, boolean hasNext) {

        static <T> KeysetPage<T> of(BiFunction<Long, Limit, List<T>> query, Long afterId, int size) {
            List<T> rows = query.apply(afterId == null ? 0L : afterId, Limit.of(size + 1));
            boolean hasNext = rows.size() > size;
            return new KeysetPage<>(hasNext ? rows.subList(0, size) : rows, hasNext);
        }

        <R> List<R> map(Function<T, R> mapper) {
            return rows.stream().map(mapper).toList();
        }

        Long nextAfterId(Function<T, Long> id) {
            return hasNext ? id.apply(rows.getLast()) : null;
        }
    }
}
