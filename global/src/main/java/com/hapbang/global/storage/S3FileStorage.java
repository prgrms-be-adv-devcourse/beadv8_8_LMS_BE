package com.hapbang.global.storage;

import java.util.Optional;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * S3 파일 업로드·조회 공통 기능. 파일 본문은 서버를 거치지 않고, 브라우저가 Presigned URL로 S3에 직접 올리고 받는다.
 * <p>
 * key는 모듈 기준(예: {@code lecture/LECTURE_THUMBNAIL/<uuid>})으로 넘긴다.
 * 환경 prefix(local·dev·prod)는 여기서 붙이므로 DB에는 모듈 기준 key를 저장한다.
 */
public class S3FileStorage {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3StorageProperties properties;

    S3FileStorage(S3Client s3Client, S3Presigner s3Presigner, S3StorageProperties properties) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.properties = properties;
    }

    /**
     * 업로드용 Presigned PUT URL. Content-Type이 서명에 들어가므로 업로드할 때 같은 값을 헤더로 보내야 한다.
     * 크기는 서명하지 않는다. 업로드 후 {@link #find(String)}로 확인한다.
     */
    public PresignedUrl presignUpload(String key, String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType이 필요합니다.");
        }
        PutObjectRequest putObject = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey(key))
                .contentType(contentType)
                .build();
        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(request -> request
                .signatureDuration(properties.uploadUrlExpiry())
                .putObjectRequest(putObject));
        return new PresignedUrl(presigned.url().toString(), presigned.expiration());
    }

    /** 조회용 Presigned GET URL. 버킷은 공개하지 않으므로 파일을 보여 줄 때마다 발급한다. */
    public PresignedUrl presignDownload(String key) {
        GetObjectRequest getObject = GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey(key))
                .build();
        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(request -> request
                .signatureDuration(properties.downloadUrlExpiry())
                .getObjectRequest(getObject));
        return new PresignedUrl(presigned.url().toString(), presigned.expiration());
    }

    /**
     * 객체가 있으면 크기와 Content-Type을 돌려준다(HEAD). 없으면 비어 있다.
     * 권한 부족(403) 등 다른 오류는 그대로 던진다. 서버 역할에 ListBucket이 없으면 없는 객체도 403이 된다.
     */
    public Optional<StoredFile> find(String key) {
        try {
            HeadObjectResponse head = s3Client.headObject(request -> request
                    .bucket(properties.bucket())
                    .key(objectKey(key)));
            return Optional.of(new StoredFile(head.contentLength(), head.contentType()));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private String objectKey(String key) {
        if (key == null || key.isBlank() || key.startsWith("/")) {
            throw new IllegalArgumentException("key는 비어 있거나 '/'로 시작할 수 없습니다: " + key);
        }
        return properties.keyPrefix() + "/" + key;
    }
}
