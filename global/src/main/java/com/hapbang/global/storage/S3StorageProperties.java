package com.hapbang.global.storage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3 설정 (hapbang.storage.s3.*).
 *
 * @param bucket            버킷 이름. 공개 레포라 서버는 환경 변수(S3_BUCKET)로만 받는다
 * @param region            리전
 * @param keyPrefix         환경 구분 prefix(local·dev·prod). 모든 object key 앞에 붙는다
 * @param endpoint          로컬 S3 에뮬레이터(S3Mock) 주소. 비어 있으면 실제 S3를 쓴다
 * @param uploadUrlExpiry   Presigned PUT URL 만료 시간
 * @param downloadUrlExpiry Presigned GET URL 만료 시간
 */
@ConfigurationProperties("hapbang.storage.s3")
record S3StorageProperties(
        String bucket,
        String region,
        String keyPrefix,
        String endpoint,
        Duration uploadUrlExpiry,
        Duration downloadUrlExpiry
) {

    /** SigV4 Presigned URL의 최대 유효 기간 */
    private static final Duration MAX_URL_EXPIRY = Duration.ofDays(7);

    S3StorageProperties {
        requireText(bucket, "bucket");
        requireText(region, "region");
        requireText(keyPrefix, "key-prefix");
        if (keyPrefix.startsWith("/") || keyPrefix.endsWith("/")) {
            throw new IllegalArgumentException("hapbang.storage.s3.key-prefix는 '/'로 시작하거나 끝날 수 없습니다: " + keyPrefix);
        }
        requireExpiry(uploadUrlExpiry, "upload-url-expiry");
        requireExpiry(downloadUrlExpiry, "download-url-expiry");
    }

    boolean usesEmulator() {
        return endpoint != null && !endpoint.isBlank();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("hapbang.storage.s3." + name + " 설정이 필요합니다.");
        }
    }

    private static void requireExpiry(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(MAX_URL_EXPIRY) > 0) {
            throw new IllegalArgumentException("hapbang.storage.s3." + name + "는 0보다 크고 7일 이하여야 합니다: " + value);
        }
    }
}
