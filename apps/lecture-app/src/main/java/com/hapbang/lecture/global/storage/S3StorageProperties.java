package com.hapbang.lecture.global.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3 설정 (hapbang.storage.s3.*).
 *
 * @param bucket    버킷 이름. 공개 레포라 서버는 환경 변수(S3_BUCKET)로만 받는다
 * @param region    리전
 * @param keyPrefix 환경 구분 prefix(local·dev·prod). 모든 object key 앞에 붙는다
 * @param endpoint  로컬 S3 에뮬레이터(S3Mock) 주소. 비어 있으면 실제 S3를 쓴다
 */
@ConfigurationProperties("hapbang.storage.s3")
record S3StorageProperties(
        String bucket,
        String region,
        String keyPrefix,
        String endpoint
) {

    S3StorageProperties {
        requireText(bucket, "bucket");
        requireText(region, "region");
        requireText(keyPrefix, "key-prefix");
        if (keyPrefix.startsWith("/") || keyPrefix.endsWith("/")) {
            throw new IllegalArgumentException("hapbang.storage.s3.key-prefix는 '/'로 시작하거나 끝날 수 없습니다: " + keyPrefix);
        }
    }

    boolean usesEmulator() {
        return endpoint != null && !endpoint.isBlank();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("hapbang.storage.s3." + name + " 설정이 필요합니다.");
        }
    }
}
