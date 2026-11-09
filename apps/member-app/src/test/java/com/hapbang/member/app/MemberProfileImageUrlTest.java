package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.hapbang.member.global.storage.PresignedUrl;
import com.hapbang.member.global.storage.S3FileStorage;
import com.hapbang.member.out.ProfileImageUrlCache;

/** 프로필 사진 조회 URL이 캐시를 거치는지 (#770). 캐시에 있으면 그대로, 없는 것만 서명해 캐시에 넣는다 */
class MemberProfileImageUrlTest {

    private final S3FileStorage s3FileStorage = mock(S3FileStorage.class);
    private final ProfileImageUrlCache cache = mock(ProfileImageUrlCache.class);
    private final MemberProfileImageUseCase useCase =
            new MemberProfileImageUseCase(mock(MemberSupport.class), s3FileStorage, cache);

    @Test
    @DisplayName("캐시에 있는 사진은 서명하지 않고, 없는 사진만 서명해 캐시에 넣는다")
    void signsOnlyMisses() {
        given(cache.findAll(anyCollection())).willReturn(Map.of("member/profile/1/a", "https://cached/a"));
        given(s3FileStorage.presignDownload(eq("member/profile/2/b"), any()))
                .willReturn(presigned("https://signed/b", null));

        Map<String, String> urls = useCase.urlsOf(List.of("member/profile/1/a", "member/profile/2/b"));

        assertThat(urls).containsOnly(entry("member/profile/1/a", "https://cached/a"),
                entry("member/profile/2/b", "https://signed/b"));
        verify(s3FileStorage, never()).presignDownload(eq("member/profile/1/a"), any());
        assertThat(putEntries()).containsExactly(
                new ProfileImageUrlCache.Entry("member/profile/2/b", "https://signed/b", Duration.ofMinutes(50)));
    }

    @Test
    @DisplayName("1시간 URL로 서명하고, 캐시 유지 시간은 자격 증명 남은 시간 − 5분을 넘지 않는다")
    void ttlFollowsCredentials() {
        given(cache.findAll(anyCollection())).willReturn(Map.of());
        given(s3FileStorage.presignDownload(anyString(), any()))
                .willReturn(presigned("https://signed/a", Instant.now().plus(Duration.ofMinutes(20))));

        useCase.urlsOf(List.of("member/profile/1/a"));

        verify(s3FileStorage).presignDownload("member/profile/1/a", Duration.ofHours(1));
        assertThat(putEntries()).singleElement().satisfies(entry ->
                assertThat(entry.ttl()).isBetween(Duration.ofMinutes(14), Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("자격 증명이 곧 끝나면 서명한 URL을 주되 캐시에 넣지 않는다")
    void doesNotCacheWhenCredentialsExpireSoon() {
        given(cache.findAll(anyCollection())).willReturn(Map.of());
        given(s3FileStorage.presignDownload(anyString(), any()))
                .willReturn(presigned("https://signed/a", Instant.now().plus(Duration.ofMinutes(3))));

        assertThat(useCase.urlsOf(List.of("member/profile/1/a"))).containsOnly(entry("member/profile/1/a", "https://signed/a"));
        assertThat(putEntries()).isEmpty();
    }

    @Test
    @DisplayName("같은 key는 한 번만 묻고, null key는 건너뛴다. 모두 없으면 캐시를 부르지 않는다")
    void distinctAndNullKeys() {
        given(cache.findAll(anyCollection())).willReturn(Map.of("member/profile/1/a", "https://cached/a"));

        assertThat(useCase.urlsOf(Arrays.asList("member/profile/1/a", null, "member/profile/1/a")))
                .containsOnly(entry("member/profile/1/a", "https://cached/a"));
        verify(cache).findAll(Set.of("member/profile/1/a"));

        assertThat(useCase.urlsOf(Arrays.asList(null, null))).isEmpty();
        assertThat(useCase.urlOf(null)).isNull();
        verify(cache).findAll(anyCollection());
    }

    @Test
    @DisplayName("단건 조회도 캐시를 거친다 (내 정보·프로필·인플루언서 목록·받은 리뷰의 profileImageUrl)")
    void urlOf() {
        given(cache.findAll(anyCollection())).willReturn(Map.of("member/profile/1/a", "https://cached/a"));

        assertThat(useCase.urlOf("member/profile/1/a")).isEqualTo("https://cached/a");
        verify(s3FileStorage, never()).presignDownload(anyString(), any());
    }

    @SuppressWarnings("unchecked")
    private List<ProfileImageUrlCache.Entry> putEntries() {
        ArgumentCaptor<List<ProfileImageUrlCache.Entry>> captor = ArgumentCaptor.forClass(List.class);
        verify(cache).putAll(captor.capture());
        return captor.getValue();
    }

    private static PresignedUrl presigned(String url, Instant credentialsExpiresAt) {
        return new PresignedUrl(url, Instant.now().plus(Duration.ofHours(1)), credentialsExpiresAt);
    }
}
