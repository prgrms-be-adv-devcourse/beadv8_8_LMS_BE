package com.hapbang.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class S3StoragePropertiesTest {

    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    @Test
    @DisplayName("버킷 이름이 없으면 시작하지 않는다")
    void requiresBucket() {
        assertThatThrownBy(() -> properties("", "dev", null, TEN_MINUTES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bucket");
    }

    @Test
    @DisplayName("key prefix는 '/'로 시작하거나 끝날 수 없다")
    void rejectsSlashInKeyPrefix() {
        assertThatThrownBy(() -> properties("bucket", "dev/", null, TEN_MINUTES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("bucket", "/dev", null, TEN_MINUTES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("만료 시간은 0보다 크고 7일 이하여야 한다")
    void validatesExpiry() {
        assertThatThrownBy(() -> properties("bucket", "dev", null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("bucket", "dev", null, Duration.ofDays(8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(properties("bucket", "dev", null, Duration.ofDays(7))).isNotNull();
    }

    @Test
    @DisplayName("endpoint가 있을 때만 S3 에뮬레이터를 쓴다")
    void usesEmulator() {
        assertThat(properties("bucket", "local", "http://localhost:9090", TEN_MINUTES).usesEmulator()).isTrue();
        assertThat(properties("bucket", "dev", "", TEN_MINUTES).usesEmulator()).isFalse();
        assertThat(properties("bucket", "dev", null, TEN_MINUTES).usesEmulator()).isFalse();
    }

    private S3StorageProperties properties(String bucket, String keyPrefix, String endpoint, Duration expiry) {
        return new S3StorageProperties(bucket, "ap-northeast-2", keyPrefix, endpoint, expiry, expiry);
    }
}
