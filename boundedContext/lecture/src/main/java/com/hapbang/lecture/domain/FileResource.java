package com.hapbang.lecture.domain;

import java.time.LocalDateTime;
import java.util.Locale;
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
 *     <li>파일 정보를 등록할 때 {@code PENDING}으로 만들고, 서버가 본문을 S3에 저장하면 {@code UPLOADED}가 된다(D24).</li>
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

    /** 바이트. 등록할 때 신고한 크기이며, 본문을 올릴 때 Content-Length와 같은지 확인한다 */
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

    /** 업로드를 마치고 삭제되지 않은 파일만 조회 URL을 발급한다 (D26) */
    public boolean isViewable() {
        return isUploaded() && deletedAt == null;
    }

    /**
     * 본문을 올릴 수 있는지 검사한다(D24). 본인 → 상태 → 길이 → 형식 순서다.
     * 길이는 요청의 Content-Length이고, 모르면(-1) 거부한다. 서버가 이 길이만큼만 S3로 보내므로 저장된 크기는 등록 크기와 같다.
     * Content-Type은 대소문자와 파라미터({@code ; charset=} 등)를 무시하고 등록한 값과 비교한다.
     */
    public void validateUploadable(Long userId, String requestContentType, long contentLength) {
        if (!uploaderUserId.equals(userId)) {
            throw new LectureException(LectureErrorCode.NOT_FILE_UPLOADER);
        }
        if (isUploaded()) {
            throw new LectureException(LectureErrorCode.FILE_ALREADY_UPLOADED);
        }
        if (contentLength < 0) {
            throw new LectureException(LectureErrorCode.FILE_LENGTH_REQUIRED);
        }
        if (contentLength != fileSize) {
            throw new LectureException(LectureErrorCode.FILE_SIZE_MISMATCH);
        }
        if (!mediaType(contentType).equals(mediaType(requestContentType))) {
            throw new LectureException(LectureErrorCode.FILE_CONTENT_TYPE_MISMATCH);
        }
    }

    /** 본문을 S3에 저장했으면 완료한다. 이미 완료했으면(같은 파일을 동시에 올린 경우) 그대로 둔다 */
    public void markUploaded(LocalDateTime now) {
        if (isUploaded()) {
            return;
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
                    "업로드가 끝나지 않은 파일입니다. 파일 본문을 먼저 올려 주세요: fileId=" + id);
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

    /** {@code type/subtype}만 소문자로. 파라미터는 버린다 */
    private static String mediaType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String type = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
        return type.strip().toLowerCase(Locale.ROOT);
    }

    private static void validateMaxSize(FileType fileType, long size) {
        if (size > fileType.maxSize()) {
            throw new LectureException(LectureErrorCode.FILE_TOO_LARGE);
        }
    }
}
