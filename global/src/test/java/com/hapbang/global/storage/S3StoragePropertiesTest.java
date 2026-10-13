package com.hapbang.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class S3StoragePropertiesTest {

    @Test
    @DisplayName("버킷 이름이 없으면 시작하지 않는다")
    void requiresBucket() {
        assertThatThrownBy(() -> properties("", "dev", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bucket");
    }

    @Test
    @DisplayName("key prefix는 '/'로 시작하거나 끝날 수 없다")
    void rejectsSlashInKeyPrefix() {
        assertThatThrownBy(() -> properties("bucket", "dev/", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("bucket", "/dev", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("endpoint가 있을 때만 S3 에뮬레이터를 쓴다")
    void usesEmulator() {
        assertThat(properties("bucket", "local", "http://localhost:9090").usesEmulator()).isTrue();
        assertThat(properties("bucket", "dev", "").usesEmulator()).isFalse();
        assertThat(properties("bucket", "dev", null).usesEmulator()).isFalse();
    }

    private S3StorageProperties properties(String bucket, String keyPrefix, String endpoint) {
        return new S3StorageProperties(bucket, "ap-northeast-2", keyPrefix, endpoint);
    }
}
