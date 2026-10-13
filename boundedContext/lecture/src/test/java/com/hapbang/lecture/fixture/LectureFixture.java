package com.hapbang.lecture.fixture;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.lecture.domain.ContentType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureContentSpec;

/**
 * 강의 테스트 공용 데이터.
 */
public final class LectureFixture {

    public static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 8, 12, 0);

    public static final Long SELLER_ID = 1L;
    public static final Long OTHER_USER_ID = 2L;
    public static final Long ADMIN_ID = 3L;

    public static final String TITLE = "방송 장비 세팅 강의";
    public static final String DESCRIPTION = "처음 방송하는 사람을 위한 장비 세팅";
    public static final long PRICE_HP = 10_000L;
    public static final Long THUMBNAIL_FILE_ID = 100L;

    private LectureFixture() {
    }

    public static List<LectureContentSpec> contentSpecs() {
        return List.of(
                new LectureContentSpec(101L, "1강 장비 고르기", ContentType.VIDEO),
                new LectureContentSpec(102L, "장비 목록", ContentType.DOCUMENT)
        );
    }

    public static Lecture pendingLecture() {
        return Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, THUMBNAIL_FILE_ID, contentSpecs(), NOW);
    }

    public static Lecture rejectedLecture() {
        Lecture lecture = pendingLecture();
        lecture.reject(ADMIN_ID, "영상 음질이 너무 낮습니다.", NOW);
        return lecture;
    }

    public static Lecture onSaleLecture() {
        Lecture lecture = pendingLecture();
        lecture.approve(ADMIN_ID, NOW);
        return lecture;
    }

    /** 저장하지 않은 강의에 ID를 넣는다. 장바구니처럼 강의 ID로 참조하는 단위 테스트용 */
    public static Lecture withId(Lecture lecture, Long lectureId) {
        ReflectionTestUtils.setField(lecture, "id", lectureId);
        return lecture;
    }
}
