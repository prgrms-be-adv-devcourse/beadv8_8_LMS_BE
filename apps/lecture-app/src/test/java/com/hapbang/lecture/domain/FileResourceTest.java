package com.hapbang.lecture.domain;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class FileResourceTest {

    private static final long SIZE = 1_024L;

    @Test
    void 파일_정보를_등록하면_PENDING이고_key는_용도와_UUID로만_만든다() {
        FileResource file = FileResource.create(SELLER_ID, FileType.LECTURE_VIDEO, "1강 장비 고르기.mp4", "video/mp4",
                SIZE, NOW);

        assertThat(file.getStatus()).isEqualTo(FileStatus.PENDING);
        assertThat(file.getUploaderUserId()).isEqualTo(SELLER_ID);
        assertThat(file.getOriginalFilename()).isEqualTo("1강 장비 고르기.mp4");
        assertThat(file.getObjectKey())
                .matches("lecture/LECTURE_VIDEO/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(file.getUploadedAt()).isNull();
        assertThat(file.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void 같은_파일을_다시_올려도_key가_겹치지_않는다() {
        FileResource first = FileResource.create(SELLER_ID, FileType.LECTURE_VIDEO, "a.mp4", "video/mp4", SIZE, NOW);
        FileResource second = FileResource.create(SELLER_ID, FileType.LECTURE_VIDEO, "a.mp4", "video/mp4", SIZE, NOW);

        assertThat(first.getObjectKey()).isNotEqualTo(second.getObjectKey());
    }

    @ParameterizedTest
    @CsvSource({
            "LECTURE_THUMBNAIL, image/gif",
            "LECTURE_THUMBNAIL, video/mp4",
            "LECTURE_VIDEO, video/webm",
            "LECTURE_VIDEO, image/png",
            "LECTURE_DOCUMENT, application/zip"
    })
    void 허용하지_않는_content_type이면_UNSUPPORTED_FILE_CONTENT_TYPE으로_거부한다(FileType fileType, String contentType) {
        assertThatThrownBy(() -> FileResource.create(SELLER_ID, fileType, "file", contentType, SIZE, NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.UNSUPPORTED_FILE_CONTENT_TYPE);
    }

    @ParameterizedTest
    @CsvSource({
            "LECTURE_THUMBNAIL, image/jpeg",
            "LECTURE_THUMBNAIL, image/png",
            "LECTURE_THUMBNAIL, image/webp",
            "LECTURE_VIDEO, video/mp4",
            "LECTURE_DOCUMENT, application/pdf"
    })
    void 용도별로_허용한_content_type은_받는다(FileType fileType, String contentType) {
        FileResource file = FileResource.create(SELLER_ID, fileType, "file", contentType, SIZE, NOW);

        assertThat(file.getContentType()).isEqualTo(contentType);
    }

    @ParameterizedTest
    @CsvSource({
            "LECTURE_THUMBNAIL, image/png, 5242880",
            "LECTURE_VIDEO, video/mp4, 2147483648",
            "LECTURE_DOCUMENT, application/pdf, 52428800"
    })
    void 용도별_최대_크기까지는_허용하고_넘으면_FILE_TOO_LARGE로_거부한다(FileType fileType, String contentType,
                                                         long maxSize) {
        assertThat(FileResource.create(SELLER_ID, fileType, "file", contentType, maxSize, NOW).getFileSize())
                .isEqualTo(maxSize);
        assertThatThrownBy(() -> FileResource.create(SELLER_ID, fileType, "file", contentType, maxSize + 1, NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.FILE_TOO_LARGE);
    }

    @Test
    void 크기가_0_이하이거나_필수_값이_없으면_INVALID_REQUEST로_거부한다() {
        assertThatThrownBy(() -> FileResource.create(SELLER_ID, FileType.LECTURE_VIDEO, "a.mp4", "video/mp4", 0, NOW))
                .extracting("errorCode").isEqualTo(LectureErrorCode.INVALID_REQUEST);
        assertThatThrownBy(() -> FileResource.create(SELLER_ID, FileType.LECTURE_VIDEO, " ", "video/mp4", SIZE, NOW))
                .extracting("errorCode").isEqualTo(LectureErrorCode.INVALID_REQUEST);
        assertThatThrownBy(() -> FileResource.create(null, FileType.LECTURE_VIDEO, "a.mp4", "video/mp4", SIZE, NOW))
                .extracting("errorCode").isEqualTo(LectureErrorCode.INVALID_REQUEST);
        assertThatThrownBy(() -> FileResource.create(SELLER_ID, null, "a.mp4", "video/mp4", SIZE, NOW))
                .extracting("errorCode").isEqualTo(LectureErrorCode.INVALID_REQUEST);
    }

    @Test
    void 업로더가_아니면_본문을_올릴_수_없다() {
        FileResource file = pendingVideo();

        assertThatThrownBy(() -> file.validateUploadable(OTHER_USER_ID, "video/mp4", SIZE))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.NOT_FILE_UPLOADER);
    }

    @Test
    void 이미_UPLOADED인_파일에는_다시_올릴_수_없다() {
        FileResource file = pendingVideo();
        file.markUploaded(NOW);

        assertThatThrownBy(() -> file.validateUploadable(SELLER_ID, "video/mp4", SIZE))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.FILE_ALREADY_UPLOADED);
    }

    @Test
    void 본문_길이를_모르면_FILE_LENGTH_REQUIRED로_거부한다() {
        FileResource file = pendingVideo();

        assertThatThrownBy(() -> file.validateUploadable(SELLER_ID, "video/mp4", -1))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.FILE_LENGTH_REQUIRED);
    }

    @Test
    void 본문_길이가_등록_크기와_다르면_FILE_SIZE_MISMATCH로_거부한다() {
        FileResource file = pendingVideo();

        assertThatThrownBy(() -> file.validateUploadable(SELLER_ID, "video/mp4", SIZE + 1))
                .extracting("errorCode").isEqualTo(LectureErrorCode.FILE_SIZE_MISMATCH);
        assertThatThrownBy(() -> file.validateUploadable(SELLER_ID, "video/mp4", SIZE - 1))
                .extracting("errorCode").isEqualTo(LectureErrorCode.FILE_SIZE_MISMATCH);
        assertThat(file.getStatus()).isEqualTo(FileStatus.PENDING);
    }

    @Test
    void Content_Type이_등록_값과_다르면_FILE_CONTENT_TYPE_MISMATCH로_거부한다() {
        FileResource file = pendingVideo();

        assertThatThrownBy(() -> file.validateUploadable(SELLER_ID, "video/webm", SIZE))
                .extracting("errorCode").isEqualTo(LectureErrorCode.FILE_CONTENT_TYPE_MISMATCH);
        assertThatThrownBy(() -> file.validateUploadable(SELLER_ID, null, SIZE))
                .extracting("errorCode").isEqualTo(LectureErrorCode.FILE_CONTENT_TYPE_MISMATCH);
        assertThatThrownBy(() -> file.validateUploadable(SELLER_ID, "", SIZE))
                .extracting("errorCode").isEqualTo(LectureErrorCode.FILE_CONTENT_TYPE_MISMATCH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"video/mp4", "VIDEO/MP4", "video/mp4; charset=binary", " video/mp4 "})
    void Content_Type은_대소문자와_파라미터를_무시하고_비교한다(String contentType) {
        FileResource file = pendingVideo();

        assertThatCode(() -> file.validateUploadable(SELLER_ID, contentType, SIZE)).doesNotThrowAnyException();
    }

    @Test
    void 업로드를_마치면_UPLOADED와_완료_시각을_기록한다() {
        FileResource file = pendingVideo();

        file.markUploaded(NOW.plusMinutes(1));

        assertThat(file.getStatus()).isEqualTo(FileStatus.UPLOADED);
        assertThat(file.isUploaded()).isTrue();
        assertThat(file.getUploadedAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(file.getUpdatedAt()).isEqualTo(NOW.plusMinutes(1));
    }

    @Test
    void 이미_UPLOADED인_파일을_다시_완료해도_그대로다() {
        FileResource file = pendingVideo();
        file.markUploaded(NOW.plusMinutes(1));

        file.markUploaded(NOW.plusMinutes(2));

        assertThat(file.getUploadedAt()).isEqualTo(NOW.plusMinutes(1));
    }

    @Test
    void 업로드를_마치고_삭제되지_않은_파일만_볼_수_있다() {
        assertThat(uploadedFile(SELLER_ID, FileType.LECTURE_THUMBNAIL).isViewable()).isTrue();
        assertThat(pendingFile(SELLER_ID, FileType.LECTURE_THUMBNAIL).isViewable()).isFalse();
        assertThat(deleted(uploadedFile(SELLER_ID, FileType.LECTURE_THUMBNAIL)).isViewable()).isFalse();
    }

    @Test
    void UPLOADED이고_본인이며_용도가_같으면_연결할_수_있다() {
        FileResource uploaded = pendingVideo();
        uploaded.markUploaded(NOW);

        assertThatCode(() -> uploaded.validateAttachableBy(SELLER_ID, FileType.LECTURE_VIDEO))
                .doesNotThrowAnyException();
    }

    @Test
    void 남의_파일_미완료_파일_용도가_다른_파일은_INVALID_LECTURE_FILE로_거부하고_메시지만_다르다() {
        FileResource pending = pendingVideo();
        FileResource uploaded = pendingVideo();
        uploaded.markUploaded(NOW);

        assertThatThrownBy(() -> uploaded.validateAttachableBy(OTHER_USER_ID, FileType.LECTURE_VIDEO))
                .isInstanceOf(LectureException.class)
                .hasMessageContaining("본인이 올린 파일이 아니거나 없는 파일")
                .extracting("errorCode").isEqualTo(LectureErrorCode.INVALID_LECTURE_FILE);
        assertThatThrownBy(() -> pending.validateAttachableBy(SELLER_ID, FileType.LECTURE_VIDEO))
                .hasMessageContaining("업로드가 끝나지 않은 파일")
                .extracting("errorCode").isEqualTo(LectureErrorCode.INVALID_LECTURE_FILE);
        assertThatThrownBy(() -> uploaded.validateAttachableBy(SELLER_ID, FileType.LECTURE_DOCUMENT))
                .hasMessageContaining("자료 자리에는 LECTURE_DOCUMENT 파일만")
                .extracting("errorCode").isEqualTo(LectureErrorCode.INVALID_LECTURE_FILE);
    }

    @ParameterizedTest
    @EnumSource(ContentType.class)
    void 콘텐츠_종류마다_연결할_파일_용도가_정해져_있다(ContentType contentType) {
        FileType expected = switch (contentType) {
            case VIDEO -> FileType.LECTURE_VIDEO;
            case DOCUMENT -> FileType.LECTURE_DOCUMENT;
        };

        assertThat(contentType.fileType()).isEqualTo(expected);
    }

    private static FileResource pendingVideo() {
        return FileResource.create(SELLER_ID, FileType.LECTURE_VIDEO, "a.mp4", "video/mp4", SIZE, NOW);
    }
}
