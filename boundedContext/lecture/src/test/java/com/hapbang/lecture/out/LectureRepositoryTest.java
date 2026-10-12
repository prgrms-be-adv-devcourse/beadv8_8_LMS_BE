package com.hapbang.lecture.out;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.lecture.domain.ContentType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureContent;
import com.hapbang.lecture.domain.LectureContentSpec;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class LectureRepositoryTest {

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 강의를_콘텐츠와_함께_저장하고_순서대로_읽는다() {
        Long lectureId = lectureRepository.save(pendingLecture()).getId();
        flushAndClear();

        Lecture found = lectureRepository.findById(lectureId).orElseThrow();

        assertThat(found.getTitle()).isEqualTo(TITLE);
        assertThat(found.getVersion()).isNotNull();
        assertThat(found.getContents())
                .extracting(LectureContent::getSequence, LectureContent::getFileId)
                .containsExactly(tuple(1, 101L), tuple(2, 102L));
    }

    @Test
    void 콘텐츠를_바꾸면_새_콘텐츠만_읽히고_기존_행은_삭제_표시로_남는다() {
        Long lectureId = lectureRepository.save(rejectedLecture()).getId();
        flushAndClear();

        Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
        lecture.replaceContents(SELLER_ID,
                List.of(new LectureContentSpec(201L, "1강 다시 찍음", ContentType.VIDEO)), NOW.plusDays(1));
        flushAndClear();

        Lecture found = lectureRepository.findById(lectureId).orElseThrow();
        assertThat(found.getContents())
                .extracting(LectureContent::getSequence, LectureContent::getFileId)
                .containsExactly(tuple(1, 201L));
        assertThat(countContents(lectureId, true)).isEqualTo(2);
        assertThat(countContents(lectureId, false)).isEqualTo(1);
    }

    private long countContents(Long lectureId, boolean deleted) {
        String condition = deleted ? "deleted_at is not null" : "deleted_at is null";
        Number count = (Number) entityManager.createNativeQuery(
                        "select count(*) from lecture.lecture_content where lecture_id = :lectureId and " + condition)
                .setParameter("lectureId", lectureId)
                .getSingleResult();
        return count.longValue();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
