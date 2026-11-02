package com.hapbang.lecture.global.storage;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import org.springframework.http.ContentDisposition;

import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * S3 파일 업로드·조회 공통 기능.
 * <ul>
 *     <li>업로드는 서버 경유다. 모듈이 받은 요청 본문을 {@link #upload}로 S3에 넘긴다.
 *     인스턴스 역할로 서명한 Presigned PUT은 EC2 밖에서 조직 정책으로 거부되므로 쓰지 않는다(docs/deploy/s3.md).</li>
 *     <li>조회는 브라우저가 Presigned GET URL로 S3에서 직접 받는다.</li>
 * </ul>
 * <p>
 * key는 모듈 기준(예: {@code lecture/LECTURE_THUMBNAIL/<uuid>})으로 넘긴다.
 * 환경 prefix(local·dev·prod)는 여기서 붙이므로 DB에는 모듈 기준 key를 저장한다.
 */
public class S3FileStorage {

    /** SigV4 Presigned URL의 최대 유효 기간 */
    private static final Duration MAX_URL_EXPIRY = Duration.ofDays(7);

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3StorageProperties properties;

    S3FileStorage(S3Client s3Client, S3Presigner s3Presigner, S3StorageProperties properties) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.properties = properties;
    }

    /**
     * 본문을 S3에 저장한다. 같은 key가 있으면 덮어쓴다.
     * <p>
     * 본문을 메모리에 모으지 않고 {@code contentLength}만큼 읽으면서 보낸다. 요청 본문은 되감을 수 없으므로 실패해도 재시도하지 않는다
     * (재시도하면 이미 읽은 부분이 빠지거나 원래 오류가 가려진다). 본문이 끊기거나 S3가 실패하면 SDK 예외를 그대로 던지고, 객체는 생기지 않는다.
     *
     * @param contentLength 본문 바이트 수. 실제 본문이 이보다 짧으면 실패한다
     */
    public void upload(String key, String contentType, long contentLength, InputStream body) {
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType이 필요합니다.");
        }
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength는 0 이상이어야 합니다: " + contentLength);
        }
        if (body == null) {
            throw new IllegalArgumentException("본문이 필요합니다.");
        }
        PutObjectRequest putObject = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey(key))
                .contentType(contentType)
                .contentLength(contentLength)
                .overrideConfiguration(request -> request.addPlugin(config -> config.overrideConfiguration(
                        client -> client.retryStrategy(AwsRetryStrategy.doNotRetry()))))
                .build();
        s3Client.putObject(putObject, RequestBody.fromInputStream(body, contentLength));
    }

    /**
     * 조회용 Presigned GET URL. 버킷은 공개하지 않으므로 파일을 보여 줄 때마다 발급한다.
     * <p>
     * 만료는 용도(썸네일·시청 등)에 맞게 호출하는 쪽이 정한다. 서버의 인스턴스 역할 자격 증명은 임시 키라서,
     * 그 키가 먼저 만료되면 URL도 {@code expiry}보다 일찍 끝날 수 있다.
     *
     * @param expiry 0보다 크고 7일(SigV4 최대) 이하
     */
    public PresignedUrl presignDownload(String key, Duration expiry) {
        return presign(GetObjectRequest.builder(), key, expiry);
    }

    /**
     * 내려받기용 Presigned GET URL. 브라우저가 열지 않고 {@code attachmentFilename}으로 저장하도록
     * {@code Content-Disposition: attachment}를 응답 헤더로 지정한다(서명에 포함되어 바꿀 수 없다).
     * 파일명은 RFC 5987({@code filename*=UTF-8''…})로 넣으므로 한글 파일명도 그대로 저장된다.
     *
     * @param expiry             0보다 크고 7일(SigV4 최대) 이하
     * @param attachmentFilename 저장할 파일명. 비어 있을 수 없다
     */
    public PresignedUrl presignDownload(String key, Duration expiry, String attachmentFilename) {
        if (attachmentFilename == null || attachmentFilename.isBlank()) {
            throw new IllegalArgumentException("내려받을 파일명이 필요합니다.");
        }
        String disposition = ContentDisposition.attachment()
                .filename(attachmentFilename, StandardCharsets.UTF_8)
                .build()
                .toString();
        return presign(GetObjectRequest.builder().responseContentDisposition(disposition), key, expiry);
    }

    private PresignedUrl presign(GetObjectRequest.Builder getObjectBuilder, String key, Duration expiry) {
        if (expiry == null || expiry.isZero() || expiry.isNegative() || expiry.compareTo(MAX_URL_EXPIRY) > 0) {
            throw new IllegalArgumentException("조회 URL 만료는 0보다 크고 7일 이하여야 합니다: " + expiry);
        }
        GetObjectRequest getObject = getObjectBuilder
                .bucket(properties.bucket())
                .key(objectKey(key))
                .build();
        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(request -> request
                .signatureDuration(expiry)
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
