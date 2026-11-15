package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.hapbang.member.global.storage.PresignedUrl;

/** 프로필 사진 조회 주소 캐시 유지 시간 (#770): min(50분, 자격 증명 남은 시간 − 10분) */
class ProfileImageUrlCacheTtlTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");

    @Test
    @DisplayName("만료가 없는 자격 증명(로컬 고정 키)이면 50분")
    void noCredentialsExpiry() {
        assertThat(ProfileImageUrlCacheTtl.of(url(null), NOW)).contains(Duration.ofMinutes(50));
    }

    @Test
    @DisplayName("자격 증명이 60분 넘게 남았으면 50분")
    void longCredentials() {
        assertThat(ProfileImageUrlCacheTtl.of(url(NOW.plus(Duration.ofHours(6))), NOW)).contains(Duration.ofMinutes(50));
        assertThat(ProfileImageUrlCacheTtl.of(url(NOW.plus(Duration.ofMinutes(60))), NOW)).contains(Duration.ofMinutes(50));
    }

    @Test
    @DisplayName("자격 증명이 60분보다 적게 남았으면 남은 시간 − 10분 (30분 남으면 20분)")
    void shortCredentials() {
        assertThat(ProfileImageUrlCacheTtl.of(url(NOW.plus(Duration.ofMinutes(30))), NOW)).contains(Duration.ofMinutes(20));
        assertThat(ProfileImageUrlCacheTtl.of(url(NOW.plus(Duration.ofMinutes(10).plusSeconds(1))), NOW))
                .contains(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("자격 증명이 10분 이하로 남았거나 이미 끝났으면 저장하지 않는다 (초 아래는 버린다)")
    void credentialsAboutToExpire() {
        assertThat(ProfileImageUrlCacheTtl.of(url(NOW.plus(Duration.ofMinutes(10).plusMillis(999))), NOW)).isEmpty();
        assertThat(ProfileImageUrlCacheTtl.of(url(NOW.plus(Duration.ofMinutes(10))), NOW)).isEmpty();
        assertThat(ProfileImageUrlCacheTtl.of(url(NOW.plus(Duration.ofMinutes(5))), NOW)).isEmpty();
        assertThat(ProfileImageUrlCacheTtl.of(url(NOW.minus(Duration.ofMinutes(1))), NOW)).isEmpty();
    }

    @ParameterizedTest(name = "자격 증명 남은 시간 {0}초")
    @ValueSource(longs = {0, 1, 599, 600, 601, 1_000, 1_800, 3_000, 3_599, 3_600, 3_601, 21_600})
    @DisplayName("캐시에서 꺼낸 주소는 어느 경우든 받은 뒤 10분 이상 쓸 수 있다 (50분 이하, 자격 증명 만료 − 10분 이하)")
    void alwaysLeavesTenMinutes(long remainingSeconds) {
        Instant credentialsExpiresAt = NOW.plusSeconds(remainingSeconds);
        PresignedUrl url = url(credentialsExpiresAt);

        ProfileImageUrlCacheTtl.of(url, NOW).ifPresent(ttl -> {
            Instant cacheEnd = NOW.plus(ttl);
            assertThat(ttl).isLessThanOrEqualTo(Duration.ofMinutes(50));
            assertThat(cacheEnd).isBeforeOrEqualTo(credentialsExpiresAt.minus(Duration.ofMinutes(10)));
            assertThat(cacheEnd).isBeforeOrEqualTo(url.expiresAt().minus(Duration.ofMinutes(10)));
        });
    }

    private static PresignedUrl url(Instant credentialsExpiresAt) {
        return new PresignedUrl("https://s3.example/presigned", NOW.plus(Duration.ofHours(1)), credentialsExpiresAt);
    }
}
