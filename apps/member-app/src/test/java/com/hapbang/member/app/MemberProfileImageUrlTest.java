package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
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
import com.hapbang.member.out.ProfileImageKey;
import com.hapbang.member.out.ProfileImageUrlCache;
import com.hapbang.member.out.UserRepository;

/** 프로필 사진 조회 URL이 캐시를 거치는지 (#770). 캐시에 있으면 그대로, 없는 것만 서명해 없을 때만 캐시에 넣는다 */
class MemberProfileImageUrlTest {

    private final S3FileStorage s3FileStorage = mock(S3FileStorage.class);
    private final ProfileImageUrlCache cache = mock(ProfileImageUrlCache.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final MemberProfileImageUseCase useCase =
            new MemberProfileImageUseCase(mock(MemberSupport.class), s3FileStorage, cache, userRepository);

    @Test
    @DisplayName("캐시에 있는 사진은 서명하지 않고, 없는 사진만 서명해 캐시에 넣는다")
    void signsOnlyMisses() {
        given(cache.findAll(anyCollection())).willReturn(available(Map.of("member/profile/1/a", "https://cached/a")));
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
        given(cache.findAll(anyCollection())).willReturn(available(Map.of()));
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
        given(cache.findAll(anyCollection())).willReturn(available(Map.of()));
        given(s3FileStorage.presignDownload(anyString(), any()))
                .willReturn(presigned("https://signed/a", Instant.now().plus(Duration.ofMinutes(3))));

        assertThat(useCase.urlsOf(List.of("member/profile/1/a"))).containsOnly(entry("member/profile/1/a", "https://signed/a"));
        verify(cache, never()).putAllIfAbsent(anyList());
    }

    @Test
    @DisplayName("같은 key는 한 번만 묻고, null key는 건너뛴다. 모두 없으면 캐시를 부르지 않는다")
    void distinctAndNullKeys() {
        given(cache.findAll(anyCollection())).willReturn(available(Map.of("member/profile/1/a", "https://cached/a")));

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
        given(cache.findAll(anyCollection())).willReturn(available(Map.of("member/profile/1/a", "https://cached/a")));

        assertThat(useCase.urlOf("member/profile/1/a")).isEqualTo("https://cached/a");
        verify(s3FileStorage, never()).presignDownload(anyString(), any());
    }

    @Test
    @DisplayName("회원별 조회: 요청한 ID마다 키가 있고(중복은 한 번, 요청 순서), 사진 key가 없는 회원은 null이다")
    void urlsOfMembers() {
        // 저장소는 정상 회원 중 사진이 있는 회원만 준다. 7번은 사진이 없거나 볼 수 없는 회원, 99번은 없는 회원
        given(userRepository.findActiveProfileImageKeys(List.of(12L, 3L, 7L, 3L, 99L)))
                .willReturn(List.of(new ProfileImageKey(3L, "member/profile/3/a"), new ProfileImageKey(12L, "member/profile/12/b")));
        given(cache.findAll(anyCollection())).willReturn(available(Map.of("member/profile/3/a", "https://cached/a", "member/profile/12/b", "https://cached/b")));

        Map<Long, String> urls = useCase.urlsOfMembers(List.of(12L, 3L, 7L, 3L, 99L));

        assertThat(urls).containsExactly(entry(12L, "https://cached/b"), entry(3L, "https://cached/a"),
                entry(7L, null), entry(99L, null));
    }

    @Test
    @DisplayName("회원별 조회: 사진이 있는 회원이 없으면 캐시를 부르지 않고 모두 null")
    void urlsOfMembersWithoutImages() {
        given(userRepository.findActiveProfileImageKeys(List.of(7L))).willReturn(List.of());

        assertThat(useCase.urlsOfMembers(List.of(7L))).containsExactly(entry(7L, null));
        verify(cache, never()).findAll(anyCollection());
    }

    @Test
    @DisplayName("동시에 다른 요청이 먼저 캐시에 넣은 주소가 있으면 그 주소를 준다 (같은 사진은 같은 주소)")
    void usesAddressStoredFirst() {
        given(cache.findAll(anyCollection())).willReturn(available(Map.of()));
        given(s3FileStorage.presignDownload(anyString(), any())).willReturn(presigned("https://signed/mine", null));
        given(cache.putAllIfAbsent(anyList())).willReturn(Map.of("member/profile/1/a", "https://signed/first"));

        assertThat(useCase.urlOf("member/profile/1/a")).isEqualTo("https://signed/first");
    }

    @Test
    @DisplayName("캐시를 읽지 못했으면(Redis 장애) 서명한 주소를 주고 저장은 건너뛴다")
    void skipsPutWhenCacheUnavailable() {
        given(cache.findAll(anyCollection())).willReturn(ProfileImageUrlCache.Lookup.unavailable());
        given(s3FileStorage.presignDownload(anyString(), any())).willReturn(presigned("https://signed/a", null));

        assertThat(useCase.urlOf("member/profile/1/a")).isEqualTo("https://signed/a");
        verify(cache, never()).putAllIfAbsent(anyList());
    }

    @SuppressWarnings("unchecked")
    private List<ProfileImageUrlCache.Entry> putEntries() {
        ArgumentCaptor<List<ProfileImageUrlCache.Entry>> captor = ArgumentCaptor.forClass(List.class);
        verify(cache).putAllIfAbsent(captor.capture());
        return captor.getValue();
    }

    private static ProfileImageUrlCache.Lookup available(Map<String, String> found) {
        return new ProfileImageUrlCache.Lookup(found, true);
    }

    private static PresignedUrl presigned(String url, Instant credentialsExpiresAt) {
        return new PresignedUrl(url, Instant.now().plus(Duration.ofHours(1)), credentialsExpiresAt);
    }
}
