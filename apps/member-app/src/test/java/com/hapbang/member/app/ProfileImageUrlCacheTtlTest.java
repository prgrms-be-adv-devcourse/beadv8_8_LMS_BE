package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.hapbang.member.global.storage.PresignedUrl;

/** 프로필 사진 조회 주소 캐시 유지 시간 (#770): min(50분, 자격 증명 남은 시간 − 5분) */
class ProfileImageUrlCacheTtlTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");

    @Test
    @DisplayName("만료가 없는 자격 증명(로컬 고정 키)이면 50분")
    void noCredentialsExpiry() {
        assertThat(ProfileImageUrlCacheTtl.of(url(null), NOW)).contains(Duration.ofMinutes(50));
    }

    @Test
    @DisplayName("자격 증명이 55분 넘게 남았으면 50분")
    void longCredentials() {
        assertThat(ProfileImageUrlCacheTtl.of(url(NOW.plus(Duration.ofHours(6))), NOW)).contains(Duration.ofMinutes(50));
        assertThat(ProfileImageUrlCacheTtl.of(url(NOW.plus(Duration.ofMinutes(55))), NOW)).contains(Duration.ofMinutes(50));
    }

    @Test
    @DisplayName("자격 증명이 55분보다 적게 남았으면 남은 시간 − 5분")
    void shortCredentials() {
        assertThat(ProfileImageUrlCacheTtl.of(url(NOW.plus(Duration.ofMinutes(30))), NOW)).contains(Duration.ofMinutes(25));
        assertThat(ProfileImageUrlCacheTtl.of(url(NOW.plus(Duration.ofMinutes(5).plusSeconds(1))), NOW))
                .contains(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("자격 증명이 5분 1초보다 적게 남았거나 이미 끝났으면 저장하지 않는다 (초 아래는 버린다)")
    void credentialsAboutToExpire() {
        assertThat(ProfileImageUrlCacheTtl.of(url(NOW.plus(Duration.ofMinutes(5).plusMillis(999))), NOW)).isEmpty();
        assertThat(ProfileImageUrlCacheTtl.of(url(NOW.plus(Duration.ofMinutes(5))), NOW)).isEmpty();
        assertThat(ProfileImageUrlCacheTtl.of(url(NOW.plus(Duration.ofMinutes(1))), NOW)).isEmpty();
        assertThat(ProfileImageUrlCacheTtl.of(url(NOW.minus(Duration.ofMinutes(1))), NOW)).isEmpty();
    }

    @ParameterizedTest(name = "자격 증명 남은 시간 {0}초")
    @ValueSource(longs = {0, 1, 299, 300, 301, 1_000, 3_000, 3_299, 3_300, 3_301, 3_600, 21_600})
    @DisplayName("캐시 유지 시간은 50분과 자격 증명 남은 시간 − 5분을 넘지 않는다")
    void neverOutlivesCredentials(long remainingSeconds) {
        Instant credentialsExpiresAt = NOW.plusSeconds(remainingSeconds);

        ProfileImageUrlCacheTtl.of(url(credentialsExpiresAt), NOW).ifPresent(ttl -> {
            assertThat(ttl).isLessThanOrEqualTo(Duration.ofMinutes(50));
            assertThat(NOW.plus(ttl)).isBeforeOrEqualTo(credentialsExpiresAt.minus(Duration.ofMinutes(5)));
        });
    }

    private static PresignedUrl url(Instant credentialsExpiresAt) {
        return new PresignedUrl("https://s3.example/presigned", NOW.plus(Duration.ofHours(1)), credentialsExpiresAt);
    }
}
