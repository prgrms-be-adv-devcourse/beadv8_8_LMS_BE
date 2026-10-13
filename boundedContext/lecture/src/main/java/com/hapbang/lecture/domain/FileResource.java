package com.hapbang.lecture.domain;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * S3에 올리는 강의 파일(썸네일·영상·자료)의 메타데이터 (설계 4.4).
 * <ul>
 *     <li>업로드 URL을 발급할 때 {@code PENDING}으로 만들고, S3에 올라간 것을 확인하면 {@code UPLOADED}가 된다.</li>
 *     <li>강의에는 {@code UPLOADED}이고 본인이 올렸고 용도가 맞는 파일만 연결한다. 한 파일을 여러 강의에 연결할 수 있다(D21).</li>
 *     <li>object key는 용도와 UUID로만 만든다. 원본 파일명은 key에 넣지 않는다.</li>
 * </ul>
 */
@Entity
@Table(name = "file_resource", schema = "lectures",
        uniqueConstraints = @UniqueConstraint(name = "uc_file_resource_object_key", columnNames = "object_key"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FileResource {

    private static final String KEY_PREFIX = "lecture/";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long uploaderUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FileType fileType;

    /** 모듈 기준 key. 환경 prefix(local·dev·prod)는 global의 S3FileStorage가 붙인다 */
    @Column(nullable = false)
    private String objectKey;

    @Column(nullable = false)
    private String originalFilename;

    @Column(nullable = false)
    private String contentType;

    /** 바이트. 업로드 URL을 받을 때 신고한 크기이며, 완료할 때 실제 크기와 같은지 확인한다 */
    @Column(nullable = false)
    private long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FileStatus status;

    private LocalDateTime uploadedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    private FileResource(Long uploaderUserId, FileType fileType, String originalFilename, String contentType,
                         long fileSize, LocalDateTime now) {
        this.uploaderUserId = uploaderUserId;
        this.fileType = fileType;
        this.objectKey = KEY_PREFIX + fileType.name() + "/" + UUID.randomUUID();
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.status = FileStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static FileResource create(
            Long uploaderUserId,
            FileType fileType,
            String originalFilename,
            String contentType,
            long fileSize,
            LocalDateTime now
    ) {
        if (uploaderUserId == null || fileType == null || originalFilename == null || originalFilename.isBlank()
                || contentType == null || contentType.isBlank() || fileSize <= 0) {
            throw new LectureException(LectureErrorCode.INVALID_REQUEST);
        }
        if (!fileType.allows(contentType)) {
            throw new LectureException(LectureErrorCode.UNSUPPORTED_FILE_CONTENT_TYPE);
        }
        validateMaxSize(fileType, fileSize);
        return new FileResource(uploaderUserId, fileType, originalFilename, contentType, fileSize, now);
    }

    public boolean isUploaded() {
        return status == FileStatus.UPLOADED;
    }

    public void validateUploader(Long userId) {
        if (!uploaderUserId.equals(userId)) {
            throw new LectureException(LectureErrorCode.NOT_FILE_UPLOADER);
        }
    }

    /**
     * S3에서 확인한 실제 크기로 업로드를 완료한다. 이미 완료했으면 그대로 둔다.
     * 크기가 다르면 {@code PENDING}으로 남겨 같은 업로드 URL 또는 새 URL로 다시 올릴 수 있게 한다.
     */
    public void markUploaded(long actualSize, LocalDateTime now) {
        if (isUploaded()) {
            return;
        }
        validateMaxSize(fileType, actualSize);
        if (actualSize != fileSize) {
            throw new LectureException(LectureErrorCode.FILE_SIZE_MISMATCH);
        }
        this.status = FileStatus.UPLOADED;
        this.uploadedAt = now;
        this.updatedAt = now;
    }

    /**
     * 업로드가 끝났고, 이 회원이 올렸고, 용도가 같아야 강의에 연결할 수 있다. 아니면 {@code INVALID_LECTURE_FILE}이고 메시지만 다르다.
     * 남의 파일은 없는 파일과 같은 메시지로 거부해 다른 회원의 파일 ID가 있는지 드러내지 않는다.
     */
    public void validateAttachableBy(Long userId, FileType expectedType) {
        if (deletedAt != null || !uploaderUserId.equals(userId)) {
            throw notAttachable(id);
        }
        if (!isUploaded()) {
            throw new LectureException(LectureErrorCode.INVALID_LECTURE_FILE,
                    "업로드가 끝나지 않은 파일입니다. 업로드 완료를 먼저 요청해 주세요: fileId=" + id);
        }
        if (fileType != expectedType) {
            throw new LectureException(LectureErrorCode.INVALID_LECTURE_FILE,
                    "%s 자리에는 %s 파일만 연결할 수 있습니다: fileId=%d"
                            .formatted(expectedType.description(), expectedType, id));
        }
    }

    /** 없거나 본인이 올리지 않은 파일 */
    public static LectureException notAttachable(Long fileId) {
        return new LectureException(LectureErrorCode.INVALID_LECTURE_FILE,
                "본인이 올린 파일이 아니거나 없는 파일입니다: fileId=" + fileId);
    }

    private static void validateMaxSize(FileType fileType, long size) {
        if (size > fileType.maxSize()) {
            throw new LectureException(LectureErrorCode.FILE_TOO_LARGE);
        }
    }
}
