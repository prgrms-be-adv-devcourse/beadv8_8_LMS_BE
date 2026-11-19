package com.hapbang.appearance.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRecruitContent;
import com.hapbang.appearance.domain.AppearanceRecruitSearch;
import com.hapbang.appearance.domain.AppearanceRecruitSort;
import com.hapbang.appearance.domain.AppearanceUser;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.RepositoryTest;

/**
 * 출연 모집 공개 목록의 검색 조건·정렬({@link AppearanceRecruitOpenQuery})을 실제 PostgreSQL에서 확인한다.
 * 모든 모집글은 모집 중이고 마감 일시가 기준 시각 뒤라, 공개 목록의 기본 조건으로는 빠지지 않는다.
 */
@RepositoryTest
class AppearanceRecruitOpenQueryTest {

    private static final Long HOST = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);
    private static final LocalDateTime DEADLINE = NOW.plusDays(2);
    private static final LocalDateTime SCHEDULED = NOW.plusDays(7);
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2, 10);

    @Autowired
    AppearanceRecruitRepository appearanceRecruitRepository;

    @Autowired
    AppearanceUserRepository appearanceUserRepository;

    private AppearanceRecruit save(Long hostId, String title, String content, LocalDateTime recruitDeadline,
                                   LocalDateTime scheduledAt, long feePerPerson, int recruitCount, int selectedCount) {
        AppearanceRecruit recruit = AppearanceRecruit.create(hostId, new AppearanceRecruitContent(title,
                "https://www.youtube.com/@host", scheduledAt, 60, content, feePerPerson, recruitCount, recruitDeadline),
                POLICY, NOW);
        recruit.markRecruiting();
        for (int i = 0; i < selectedCount; i++) {
            recruit.increaseSelected();
        }
        return appearanceRecruitRepository.saveAndFlush(recruit);
    }

    private AppearanceRecruit withText(Long hostId, String title, String content) {
        return save(hostId, title, content, DEADLINE, SCHEDULED, 30_000, 3, 0);
    }

    private AppearanceRecruit scheduledAt(LocalDateTime scheduledAt) {
        return save(HOST, "합방", "설명", DEADLINE, scheduledAt, 30_000, 3, 0);
    }

    private AppearanceRecruit withFee(long feePerPerson) {
        return save(HOST, "합방", "설명", DEADLINE, SCHEDULED, feePerPerson, 3, 0);
    }

    private AppearanceRecruit withSeats(int recruitCount, int selectedCount) {
        return save(HOST, "합방", "설명", DEADLINE, SCHEDULED, 30_000, recruitCount, selectedCount);
    }

    private void saveUser(long id, String nickname) {
        appearanceUserRepository.saveAndFlush(AppearanceUser.from(
                new UserDto(id, nickname, Role.INFLUENCER, UserStatus.ACTIVE)));
    }

    private Page<AppearanceRecruit> find(AppearanceRecruitSearch search) {
        return appearanceRecruitRepository.findAll(AppearanceRecruitOpenQuery.where(NOW, search),
                PageRequest.of(0, 20, AppearanceRecruitOpenQuery.orderOf(search.sort())));
    }

    private List<Long> ids(AppearanceRecruitSearch search) {
        return find(search).map(AppearanceRecruit::getId).getContent();
    }

    private static List<Long> ids(AppearanceRecruit... recruits) {
        return Arrays.stream(recruits).map(AppearanceRecruit::getId).toList();
    }

    private static AppearanceRecruitSearch keyword(String keyword) {
        return new AppearanceRecruitSearch(keyword, null, null, null, null, false, null);
    }

    private static AppearanceRecruitSearch scheduled(LocalDate from, LocalDate to) {
        return new AppearanceRecruitSearch(null, from, to, null, null, false, null);
    }

    private static AppearanceRecruitSearch fee(Long min, Long max) {
        return new AppearanceRecruitSearch(null, null, null, min, max, false, null);
    }

    private static AppearanceRecruitSearch sorted(AppearanceRecruitSort sort) {
        return new AppearanceRecruitSearch(null, null, null, null, null, false, sort);
    }

    @Test
    @DisplayName("검색어는 제목·콘텐츠 설명에서 대소문자를 무시하고 부분 일치로 찾는다")
    void keywordInTitleOrContent() {
        AppearanceRecruit inTitle = withText(HOST, "Minecraft 건축 합방", "같이 지어요");
        AppearanceRecruit inContent = withText(HOST, "주말 합방", "MINECRAFT 서버에서 만나요");
        withText(HOST, "요리 합방", "같이 만들어요");

        assertThat(ids(keyword("minecraft"))).containsExactly(inContent.getId(), inTitle.getId());
        assertThat(ids(keyword("craft 건축"))).containsExactly(inTitle.getId());
        assertThat(ids(keyword("없는 검색어"))).isEmpty();
    }

    @Test
    @DisplayName("검색어는 그 모집글 호스트의 닉네임(출연 모듈 복제 회원)에서도 찾는다")
    void keywordInHostNickname() {
        saveUser(5L, "GameKing");
        saveUser(6L, "요리왕");
        AppearanceRecruit byGameKing = withText(5L, "주말 합방", "같이 해요");
        withText(6L, "주말 합방", "같이 해요");
        withText(7L, "주말 합방", "같이 해요"); // 복제 회원 정보가 없는 호스트

        assertThat(ids(keyword("gameking"))).containsExactly(byGameKing.getId());
        assertThat(ids(keyword("king"))).containsExactly(byGameKing.getId());
    }

    @Test
    @DisplayName("검색어의 %·_·\\는 와일드카드가 아니라 글자 그대로 찾는다")
    void keywordEscapesWildcards() {
        AppearanceRecruit percent = withText(HOST, "참가비 50% 지원", "설명");
        withText(HOST, "참가자 500명", "설명");
        AppearanceRecruit underscore = withText(HOST, "team_a 모집", "설명");
        withText(HOST, "teamXa 모집", "설명");
        AppearanceRecruit backslash = withText(HOST, "경로 c:\\games", "설명");

        assertThat(ids(keyword("50%"))).containsExactly(percent.getId());
        assertThat(ids(keyword("m_a"))).containsExactly(underscore.getId());
        assertThat(ids(keyword(":\\g"))).containsExactly(backslash.getId());
    }

    @Test
    @DisplayName("방송 날짜 범위는 KST 날짜로 양 끝 날짜 하루 전체를 포함한다")
    void scheduledDateRange() {
        AppearanceRecruit dayBefore = scheduledAt(LocalDateTime.of(2026, 10, 20, 23, 59, 59, 999_999_000));
        AppearanceRecruit startOfDay = scheduledAt(LocalDateTime.of(2026, 10, 21, 0, 0));
        AppearanceRecruit endOfDay = scheduledAt(LocalDateTime.of(2026, 10, 21, 23, 59, 59, 999_999_000));
        AppearanceRecruit nextDay = scheduledAt(LocalDateTime.of(2026, 10, 22, 0, 0));
        LocalDate day = LocalDate.of(2026, 10, 21);

        assertThat(ids(scheduled(day, day))).containsExactly(endOfDay.getId(), startOfDay.getId());
        assertThat(ids(scheduled(day, null))).containsExactly(nextDay.getId(), endOfDay.getId(), startOfDay.getId());
        assertThat(ids(scheduled(null, day)))
                .containsExactly(endOfDay.getId(), startOfDay.getId(), dayBefore.getId());
    }

    @Test
    @DisplayName("1인당 출연료 범위는 최소·최대 값을 포함한다")
    void feeRange() {
        AppearanceRecruit low = withFee(20_000);
        AppearanceRecruit middle = withFee(30_000);
        AppearanceRecruit high = withFee(50_000);

        assertThat(ids(fee(30_000L, null))).containsExactly(high.getId(), middle.getId());
        assertThat(ids(fee(null, 30_000L))).containsExactly(middle.getId(), low.getId());
        assertThat(ids(fee(30_000L, 30_000L))).containsExactly(middle.getId());
        assertThat(ids(fee(0L, 19_999L))).isEmpty();
    }

    @Test
    @DisplayName("남은 자리 조건은 성립 인원이 모집 인원보다 적은 모집글만 남긴다. false면 조건 없음")
    void hasSeats() {
        AppearanceRecruit empty = withSeats(2, 0);
        AppearanceRecruit partly = withSeats(2, 1);
        AppearanceRecruit full = withSeats(1, 1);

        assertThat(ids(new AppearanceRecruitSearch(null, null, null, null, null, true, null)))
                .containsExactly(partly.getId(), empty.getId());
        assertThat(ids(AppearanceRecruitSearch.NONE)).containsExactly(full.getId(), partly.getId(), empty.getId());
    }

    @Test
    @DisplayName("조건을 함께 쓰면 모두 만족하는 모집글만 남고 전체 건수도 그 기준이다")
    void combined() {
        LocalDateTime day = LocalDateTime.of(2026, 10, 21, 20, 0);
        AppearanceRecruit match = save(HOST, "롤 내전 합방", "설명", DEADLINE, day, 40_000, 3, 1);
        save(HOST, "롤 내전 합방", "설명", DEADLINE, day, 40_000, 1, 1); // 자리 없음
        save(HOST, "롤 내전 합방", "설명", DEADLINE, day, 20_000, 3, 0); // 출연료 범위 밖
        save(HOST, "롤 내전 합방", "설명", DEADLINE, day.plusDays(1), 40_000, 3, 0); // 날짜 범위 밖
        save(HOST, "배그 합방", "설명", DEADLINE, day, 40_000, 3, 0); // 검색어 없음
        AppearanceRecruitSearch search = new AppearanceRecruitSearch("롤", LocalDate.of(2026, 10, 21),
                LocalDate.of(2026, 10, 21), 30_000L, 50_000L, true, AppearanceRecruitSort.FEE_HIGH);

        Page<AppearanceRecruit> found = find(search);

        assertThat(found.getContent()).extracting(AppearanceRecruit::getId).containsExactly(match.getId());
        assertThat(found.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("네 가지 정렬: 기준 값이 같으면 최신순(나중에 올린 글이 먼저)")
    void sorts() {
        AppearanceRecruit first = save(HOST, "합방", "설명", NOW.plusDays(3), NOW.plusDays(5), 30_000, 3, 0);
        AppearanceRecruit second = save(HOST, "합방", "설명", NOW.plusDays(4), NOW.plusDays(8), 50_000, 3, 0);
        AppearanceRecruit third = save(HOST, "합방", "설명", NOW.plusDays(3), NOW.plusDays(8), 30_000, 3, 0);

        assertThat(ids(sorted(AppearanceRecruitSort.LATEST))).isEqualTo(ids(third, second, first));
        assertThat(ids(sorted(AppearanceRecruitSort.SCHEDULED_SOON))).isEqualTo(ids(first, third, second));
        assertThat(ids(sorted(AppearanceRecruitSort.DEADLINE_SOON))).isEqualTo(ids(third, first, second));
        assertThat(ids(sorted(AppearanceRecruitSort.FEE_HIGH))).isEqualTo(ids(second, third, first));
    }
}
