package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import org.hamcrest.Matchers;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.member.GatewayLogin;
import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.ProfileImage;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.ProfileImageUrlCache;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.appearance.dto.AppearanceParticipantRole;
import com.hapbang.shared.appearance.dto.AppearanceReceivedReviewDto;
import com.hapbang.shared.appearance.dto.AppearanceReceivedReviewPageDto;
import com.hapbang.shared.appearance.out.AppearanceApiClient;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.testsupport.RedisTestcontainersConfig;
import com.hapbang.testsupport.S3MockTestcontainersConfig;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManagerFactory;

/**
 * 프로필 사진 주소 일괄 조회와 서명 주소 캐시 (#770). HTTP부터 DB·Redis·S3Mock 서명까지 확인한다.
 * 캐시 유지 시간 계산은 ProfileImageUrlCacheTtlTest, 회로는 RedisProfileImageUrlCacheFailureTest가 맡는다
 */
@MemberIntegrationTest
@AutoConfigureMockMvc
@Import({S3MockTestcontainersConfig.class, RedisTestcontainersConfig.class})
@TestPropertySource(properties = {
        // 모듈 테스트 설정은 application.yaml을 읽지 않으므로 운영과 같은 Redis 시간 제한(1초)을 넣는다. 없으면 멈춘 Redis를 60초씩 기다린다
        "spring.data.redis.timeout=1s",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        // 세션마다 남는 통계 로그를 끈다
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
})
class ProfileImagesApiTest {

    private static final String CACHE_KEY_PREFIX = "member:profile-image-url:";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    /** 목록이 한 페이지의 사진을 캐시에서 한 번에 읽는지 센다 (실제 Redis를 그대로 쓴다) */
    @MockitoSpyBean
    ProfileImageUrlCache profileImageUrlCache;

    @MockitoBean
    AppearanceApiClient appearanceApiClient;

    private User viewer;

    /** 테스트끼리 Redis를 같이 쓰므로 자기가 만든 사진 key의 캐시만 지운다 (docs/dev/testing.md) */
    private final List<String> createdImageKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        viewer = userRepository.save(MemberFixture.user());
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(createdImageKeys.stream().map(key -> CACHE_KEY_PREFIX + key).toList());
    }

    @Test
    @DisplayName("요청한 ID마다 키가 있고, 사진 없음·없는 회원·탈퇴·정지 회원은 null이다")
    void urlsById() throws Exception {
        User withImage = withImage(MemberFixture.member(Role.INFLUENCER));
        User withoutImage = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        User withdrawn = withImage(MemberFixture.user());
        withdrawn.withdraw(LocalDateTime.now());
        userRepository.save(withdrawn);
        User suspended = withImage(MemberFixture.member(Role.INFLUENCER, UserStatus.SUSPENDED));
        long missingId = suspended.getId() + 1000;

        Map<String, String> data = profileImages(withImage.getId(), withoutImage.getId(), withdrawn.getId(),
                suspended.getId(), missingId, withImage.getId());

        assertThat(data).containsOnlyKeys(String.valueOf(withImage.getId()), String.valueOf(withoutImage.getId()),
                String.valueOf(withdrawn.getId()), String.valueOf(suspended.getId()), String.valueOf(missingId));
        assertThat(data.get(String.valueOf(withImage.getId()))).contains(withImage.getProfileImageKey())
                .contains("response-cache-control=private");
        assertThat(data.get(String.valueOf(withoutImage.getId()))).isNull();
        assertThat(data.get(String.valueOf(withdrawn.getId()))).isNull();
        assertThat(data.get(String.valueOf(suspended.getId()))).isNull();
        assertThat(data.get(String.valueOf(missingId))).isNull();
    }

    @Test
    @DisplayName("같은 사진을 두 번 조회하면 같은 주소다. 캐시는 50분을 넘지 않고, 사진을 바꾸면 새 주소가 된다")
    void sameImageSameUrl() throws Exception {
        User influencer = withImage(MemberFixture.member(Role.INFLUENCER));
        String id = String.valueOf(influencer.getId());

        String first = profileImages(influencer.getId()).get(id);
        String second = profileImages(influencer.getId()).get(id);

        assertThat(second).isEqualTo(first);
        assertThat(redisTemplate.getExpire(CACHE_KEY_PREFIX + influencer.getProfileImageKey(), TimeUnit.SECONDS))
                .isBetween(1L, Duration.ofMinutes(50).toSeconds());

        User changedImage = withImage(influencer);
        String changed = profileImages(influencer.getId()).get(id);

        assertThat(changed).isNotEqualTo(first).contains(changedImage.getProfileImageKey());
    }

    @Test
    @DisplayName("기존 응답의 profileImageUrl도 같은 캐시를 거쳐 일괄 조회와 같은 주소다")
    void existingResponsesUseCache() throws Exception {
        User influencer = withImage(MemberFixture.member(Role.INFLUENCER));
        String url = profileImages(influencer.getId()).get(String.valueOf(influencer.getId()));

        mockMvc.perform(get("/api/v1/members/{memberId}", influencer.getId()).with(login(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").value(url));
        mockMvc.perform(get("/api/v1/members/me").with(login(influencer)))
                .andExpect(jsonPath("$.data.profileImageUrl").value(url));
    }

    @Test
    @DisplayName("회원 수가 늘어도 SQL 문 수가 같다 (N+1 없음)")
    void noNPlusOne() throws Exception {
        List<User> influencers = IntStream.range(0, 20)
                .mapToObj(i -> withImage(MemberFixture.member(Role.INFLUENCER))).toList();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        statistics.clear();
        profileImages(influencers.getFirst().getId());
        long oneMember = statistics.getPrepareStatementCount();

        statistics.clear();
        profileImages(influencers.stream().mapToLong(User::getId).toArray());
        long twentyMembers = statistics.getPrepareStatementCount();

        assertThat(twentyMembers).isEqualTo(oneMember);
    }

    @Test
    @DisplayName("인플루언서 목록은 한 페이지의 사진 주소를 캐시에서 한 번에 읽고 한 번에 넣으며, 콘텐츠 분야(#711)도 함께 나온다")
    void influencerListReadsCacheOnce() throws Exception {
        List<User> influencers = IntStream.range(0, 3)
                .mapToObj(i -> withImage(MemberFixture.member(Role.INFLUENCER))).toList();
        User withCategories = MemberFixture.member(Role.INFLUENCER);
        withCategories.changeContentCategories(List.of("GAME", "MUKBANG"));
        withCategories = userRepository.save(withCategories);
        clearInvocations(profileImageUrlCache);

        mockMvc.perform(get("/api/v1/members/influencers").with(login(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(4))
                // 최신 가입순: 분야를 고른 회원(사진 없음)이 맨 앞, 사진이 있는 회원들이 뒤
                .andExpect(jsonPath("$.data.content[0].id").value(withCategories.getId()))
                .andExpect(jsonPath("$.data.content[0].contentCategories[0].code").value("GAME"))
                .andExpect(jsonPath("$.data.content[0].contentCategories[0].name").value("게임"))
                .andExpect(jsonPath("$.data.content[0].contentCategories[1].code").value("MUKBANG"))
                .andExpect(jsonPath("$.data.content[0].profileImageUrl").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.content[1].profileImageUrl")
                        .value(Matchers.containsString(influencers.getLast().getProfileImageKey())))
                .andExpect(jsonPath("$.data.content[1].contentCategories").isEmpty());

        verify(profileImageUrlCache).findAll(Set.copyOf(influencers.stream().map(User::getProfileImageKey).toList()));
        verify(profileImageUrlCache).putAllIfAbsent(anyList());
    }

    @Test
    @DisplayName("받은 출연 리뷰 목록은 쓴 회원들의 사진 주소를 캐시에서 한 번에 읽는다")
    void appearanceReviewListReadsCacheOnce() throws Exception {
        User target = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        User first = withImage(MemberFixture.user());
        User second = withImage(MemberFixture.user());
        User withoutImage = userRepository.save(MemberFixture.user());
        given(appearanceApiClient.getReceivedReviews(target.getId(), 0, 20)).willReturn(new AppearanceReceivedReviewPageDto(
                List.of(review(3, first.getId()), review(2, second.getId()), review(1, withoutImage.getId())),
                new AppearanceReceivedReviewPageDto.PageMetadata(20, 0, 3, 1)));
        clearInvocations(profileImageUrlCache);

        mockMvc.perform(get("/api/v1/members/{memberId}/appearance-reviews", target.getId()).with(login(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reviewer.profileImageUrl").value(Matchers.containsString(first.getProfileImageKey())))
                .andExpect(jsonPath("$.data.content[1].reviewer.profileImageUrl").value(Matchers.containsString(second.getProfileImageKey())))
                .andExpect(jsonPath("$.data.content[2].reviewer.profileImageUrl").value(Matchers.nullValue()));

        verify(profileImageUrlCache).findAll(Set.of(first.getProfileImageKey(), second.getProfileImageKey()));
    }

    @Test
    @DisplayName("Redis가 멈춰도 200과 주소를 준다 (캐시 없이 서명)")
    void redisDown() throws Exception {
        User influencer = withImage(MemberFixture.member(Role.INFLUENCER));

        RedisTestcontainersConfig.withRedisPaused(() -> {
            try {
                Map<String, String> data = profileImages(influencer.getId());
                assertThat(data.get(String.valueOf(influencer.getId()))).contains(influencer.getProfileImageKey());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    @DisplayName("ids가 없거나 0개·101개면 400 INVALID_REQUEST, 100개는 된다")
    void idsCount() throws Exception {
        mockMvc.perform(get("/api/v1/members/profile-images").with(login(viewer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/members/profile-images").param("ids", "").with(login(viewer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(request(LongStream.rangeClosed(1, 101).toArray()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(request(LongStream.rangeClosed(1, 100).toArray()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("숫자가 아닌 ID면 400 INVALID_REQUEST")
    void invalidId() throws Exception {
        mockMvc.perform(get("/api/v1/members/profile-images").param("ids", "1,abc").with(login(viewer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void notLoggedIn() throws Exception {
        mockMvc.perform(get("/api/v1/members/profile-images").param("ids", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Swagger 문서에 일괄 조회 API와 자리표시자 예시가 있다")
    void swagger() throws Exception {
        mockMvc.perform(get("/api/v1/members/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/members/profile-images'].get.summary").value("프로필 사진 주소 일괄 조회"));
    }

    /** 사진 key를 새로 붙인다(업로드는 ProfileImageApiTest가 확인한다). 서명은 객체가 없어도 된다 */
    private User withImage(User user) {
        User saved = userRepository.save(user);
        saved.changeProfileImage(ProfileImage.newKey(saved.getId()));
        createdImageKeys.add(saved.getProfileImageKey());
        return userRepository.save(saved);
    }

    private Map<String, String> profileImages(long... ids) throws Exception {
        String body = mockMvc.perform(request(ids))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("프로필 사진 주소를 조회했습니다."))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data");
    }

    private static AppearanceReceivedReviewDto review(long reviewId, long reviewerId) {
        return new AppearanceReceivedReviewDto(reviewId, 100 + reviewId, reviewerId, AppearanceParticipantRole.HOST, 5, null,
                LocalDateTime.of(2026, 11, 2, 15, 30));
    }

    private MockHttpServletRequestBuilder request(long... ids) {
        String joined = LongStream.of(ids).mapToObj(String::valueOf).collect(Collectors.joining(","));
        return get("/api/v1/members/profile-images").param("ids", joined).with(login(viewer));
    }

    private static RequestPostProcessor login(User user) {
        return GatewayLogin.as(user.getId(), user.getRole());
    }
}
