package com.hapbang.auxiliary.chatbot.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToLongFunction;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hapbang.auxiliary.chatbot.domain.SyncType;
import com.hapbang.auxiliary.chatbot.out.CopyWrite;
import com.hapbang.auxiliary.chatbot.out.LectureCopyStore;
import com.hapbang.auxiliary.chatbot.out.LectureSyncSource;
import com.hapbang.auxiliary.chatbot.out.MemberSyncSource;
import com.hapbang.auxiliary.chatbot.out.MissingCopy;
import com.hapbang.shared.lecture.dto.CartItemSyncDto;
import com.hapbang.shared.lecture.dto.CartItemSyncPageDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncPageDto;
import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.dto.UserDto;

/**
 * 회원·강의 싱크 5종 (#717). 출연 싱크(#716 {@code AppearanceSyncTarget})와 같은 일을 하므로, 종류마다 클래스를 만들지 않고
 * 원본 읽기·적용기·원본 없음 표시를 이어 붙인다.
 * <ul>
 *     <li>회원: 내부 회원 목록 API. 회원은 하드 삭제가 없어 원본 없음을 표시하지 않는다(docs/api/internal-members.md).</li>
 *     <li>강의 4종: 강의 동기화 내부 API(docs/api/internal-lecture-sync.md). 넷 다 원본 없음을 표시한다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class CopySyncTargets {

    @Bean
    SyncTarget<UserDto> memberSyncTarget(MemberSyncSource source, CopyApplier applier) {
        return new CopySyncTarget<>(SyncType.MEMBER, ChatbotJob.MEMBER_SYNC,
                pages(source::members, MemberPageDto::items, MemberPageDto::nextAfterId),
                UserDto::id, UserDto::version, (user, runId) -> applier.apply(user),
                (runId, startedAt) -> List.of());
    }

    @Bean
    SyncTarget<LectureSyncDto> lectureSyncTarget(LectureSyncSource source, CopyApplier applier, LectureCopyStore store) {
        return new CopySyncTarget<>(SyncType.LECTURE, ChatbotJob.LECTURE_SYNC,
                pages(source::lectures, LectureSyncPageDto::items, LectureSyncPageDto::nextAfterId),
                LectureSyncDto::id, LectureSyncDto::version, applier::apply, store::markMissingLectures);
    }

    @Bean
    SyncTarget<LecturePurchaseSyncDto> lecturePurchaseSyncTarget(LectureSyncSource source, CopyApplier applier,
            LectureCopyStore store) {
        return new CopySyncTarget<>(SyncType.LECTURE_PURCHASE, ChatbotJob.LECTURE_PURCHASE_SYNC,
                pages(source::purchases, LecturePurchaseSyncPageDto::items, LecturePurchaseSyncPageDto::nextAfterId),
                LecturePurchaseSyncDto::orderItemId, LecturePurchaseSyncDto::version, applier::apply,
                store::markMissingPurchases);
    }

    @Bean
    SyncTarget<CartItemSyncDto> cartItemSyncTarget(LectureSyncSource source, CopyApplier applier, LectureCopyStore store) {
        return new CopySyncTarget<>(SyncType.CART_ITEM, ChatbotJob.CART_ITEM_SYNC,
                pages(source::cartItems, CartItemSyncPageDto::items, CartItemSyncPageDto::nextAfterId),
                CartItemSyncDto::id, CartItemSyncDto::version, applier::apply, store::markMissingCartItems);
    }

    @Bean
    SyncTarget<LectureReviewSyncDto> lectureReviewSyncTarget(LectureSyncSource source, CopyApplier applier,
            LectureCopyStore store) {
        return new CopySyncTarget<>(SyncType.LECTURE_REVIEW, ChatbotJob.LECTURE_REVIEW_SYNC,
                pages(source::reviews, LectureReviewSyncPageDto::items, LectureReviewSyncPageDto::nextAfterId),
                LectureReviewSyncDto::id, LectureReviewSyncDto::version, applier::apply, store::markMissingReviews);
    }

    // 원본 API의 페이지 DTO를 싱크 페이지로 바꾸는 읽기
    private static <T, P> BiFunction<Long, Integer, SyncTarget.Page<T>> pages(BiFunction<Long, Integer, P> fetch,
            Function<P, List<T>> items, Function<P, Long> nextAfterId) {
        return (afterId, size) -> {
            P page = fetch.apply(afterId, size);
            return new SyncTarget.Page<>(items.apply(page), nextAfterId.apply(page));
        };
    }

    /** 원본 읽기·적용·원본 없음 표시를 이어 붙인 싱크 한 종류 */
    private record CopySyncTarget<T>(
            SyncType type,
            ChatbotJob job,
            BiFunction<Long, Integer, SyncTarget.Page<T>> fetcher,
            ToLongFunction<T> id,
            Function<T, Long> version,
            BiFunction<T, Long, CopyWrite> applier,
            BiFunction<Long, LocalDateTime, List<MissingCopy>> missing
    ) implements SyncTarget<T> {

        @Override
        public SyncTarget.Page<T> fetch(Long afterId, int size) {
            return fetcher.apply(afterId, size);
        }

        @Override
        public long idOf(T item) {
            return id.applyAsLong(item);
        }

        @Override
        public Long versionOf(T item) {
            return version.apply(item);
        }

        @Override
        public CopyWrite apply(T item, long runId) {
            return applier.apply(item, runId);
        }

        @Override
        public List<MissingCopy> markMissing(long runId, LocalDateTime startedAt) {
            return missing.apply(runId, startedAt);
        }
    }
}
