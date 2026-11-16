package com.hapbang.auxiliary.search.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.hapbang.auxiliary.search.lecture.out.LectureSyncSource;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncPageDto;

/**
 * 강의 앱 대신 강의·리뷰 전체를 주는 가짜(#884). ID 오름차순으로 페이지 크기대로 잘라 키셋처럼 준다. 같은 ID를 다시 넣으면 바꾼다(원본 수정).
 * {@link #onLecturePage}로 강의 페이지를 준 직후에 할 일(적재 중에 강의가 바뀌는 상황)을 끼운다.
 */
public class FakeLectureSyncSource implements LectureSyncSource {

    private final List<LectureSyncDto> lectures = new ArrayList<>();
    private final List<LectureReviewSyncDto> reviews = new ArrayList<>();
    private final AtomicInteger lectureCalls = new AtomicInteger();
    private int pageSize = Integer.MAX_VALUE;
    private Runnable onLecturePage = () -> {
    };

    /** 강의를 넣거나(같은 ID면) 바꾼다 */
    public FakeLectureSyncSource lecture(LectureSyncDto lecture) {
        lectures.removeIf(l -> l.id().equals(lecture.id()));
        lectures.add(lecture);
        lectures.sort(Comparator.comparing(LectureSyncDto::id));
        return this;
    }

    /** 리뷰를 넣거나(같은 ID면) 바꾼다 */
    public FakeLectureSyncSource review(LectureReviewSyncDto review) {
        reviews.removeIf(r -> r.id().equals(review.id()));
        reviews.add(review);
        reviews.sort(Comparator.comparing(LectureReviewSyncDto::id));
        return this;
    }

    /** 페이지 크기를 실제 상한보다 작게 해 여러 페이지를 흉내 낸다 */
    public FakeLectureSyncSource pageSize(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    /** 강의 페이지를 줄 때마다(돌려주기 직전에) 할 일 */
    public FakeLectureSyncSource onLecturePage(Runnable onLecturePage) {
        this.onLecturePage = onLecturePage;
        return this;
    }

    /** 지금까지 받은 강의 페이지 요청 수 */
    public int lectureCalls() {
        return lectureCalls.get();
    }

    @Override
    public LectureSyncPageDto lectures(Long afterId, int size) {
        lectureCalls.incrementAndGet();
        List<LectureSyncDto> page = page(lectures, LectureSyncDto::id, afterId, size);
        onLecturePage.run();
        return new LectureSyncPageDto(page, next(lectures, page, LectureSyncDto::id));
    }

    @Override
    public LectureReviewSyncPageDto reviews(Long afterId, int size) {
        List<LectureReviewSyncDto> page = page(reviews, LectureReviewSyncDto::id, afterId, size);
        return new LectureReviewSyncPageDto(page, next(reviews, page, LectureReviewSyncDto::id));
    }

    private <T> List<T> page(List<T> all, Function<T, Long> id, Long afterId, int size) {
        return all.stream().filter(item -> afterId == null || id.apply(item) > afterId)
                .limit(Math.min(size, pageSize)).toList();
    }

    private static <T> Long next(List<T> all, List<T> page, Function<T, Long> id) {
        boolean last = page.isEmpty() || id.apply(page.getLast()).equals(id.apply(all.getLast()));
        return last ? null : id.apply(page.getLast());
    }
}
