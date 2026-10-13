package com.hapbang.lecture.fixture;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.lecture.domain.ContentType;
import com.hapbang.lecture.domain.FileResource;
import com.hapbang.lecture.domain.FileType;
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

    /** 파일 정보만 등록한(본문을 올리지 않은) 파일 */
    public static FileResource pendingFile(Long uploaderUserId, FileType fileType) {
        String contentType = switch (fileType) {
            case LECTURE_THUMBNAIL -> "image/png";
            case LECTURE_VIDEO -> "video/mp4";
            case LECTURE_DOCUMENT -> "application/pdf";
        };
        return FileResource.create(uploaderUserId, fileType, "file", contentType, 1_024L, NOW);
    }

    /** 업로드를 완료해 강의에 연결할 수 있는 파일. S3 없이 강의 API를 테스트할 때 저장해서 쓴다 */
    public static FileResource uploadedFile(Long uploaderUserId, FileType fileType) {
        FileResource file = pendingFile(uploaderUserId, fileType);
        file.markUploaded(NOW);
        return file;
    }

    /** 파일을 삭제된 상태로 만든다. 파일 삭제 API가 아직 없어 필드를 직접 바꾼다 */
    public static FileResource deleted(FileResource file) {
        ReflectionTestUtils.setField(file, "deletedAt", NOW);
        return file;
    }

    /** 저장하지 않은 강의에 ID를 넣는다. 장바구니처럼 강의 ID로 참조하는 단위 테스트용 */
    public static Lecture withId(Lecture lecture, Long lectureId) {
        ReflectionTestUtils.setField(lecture, "id", lectureId);
        return lecture;
    }
}
