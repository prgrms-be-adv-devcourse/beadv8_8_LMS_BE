package com.hapbang.appearance.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceApplication;
import com.hapbang.appearance.domain.AppearanceApplicationStatus;
import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.out.AppearanceApplicationRepository.RecruitApplicantCount;
import com.hapbang.testsupport.RepositoryTest;

@RepositoryTest
class AppearanceApplicationRepositoryTest {

    private static final Long HOST = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2, 10);
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id"));
    private static final List<AppearanceApplicationStatus> ALL = List.of(AppearanceApplicationStatus.values());

    @Autowired
    AppearanceApplicationRepository appearanceApplicationRepository;

    @Autowired
    AppearanceRecruitRepository appearanceRecruitRepository;

    @Autowired
    AppearanceRepository appearanceRepository;

    private AppearanceRecruit saveRecruit() {
        AppearanceRecruit recruit = AppearanceRecruit.create(HOST, AppearanceRecruitFixture.content(NOW), POLICY, NOW);
        recruit.markRecruiting();
        return appearanceRecruitRepository.saveAndFlush(recruit);
    }

    private AppearanceApplication saveApplication(AppearanceRecruit recruit, Long guestId) {
        return appearanceApplicationRepository.saveAndFlush(
                AppearanceApplication.apply(recruit, guestId, "지원합니다.", NOW));
    }

    @Test
    @DisplayName("appearances.applications에 저장하고 조회한다 (APPLIED, 메시지, 버전·생성 시각)")
    void saveAndFind() {
        AppearanceApplication saved = saveApplication(saveRecruit(), 2L);

        AppearanceApplication found = appearanceApplicationRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(AppearanceApplicationStatus.APPLIED);
        assertThat(found.getMessage()).isEqualTo("지원합니다.");
        assertThat(found.getVersion()).isNotNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 게스트가 같은 모집글에 두 번 지원하면 DB가 막는다 (uk_appearance_applications_recruit_guest)")
    void uniqueRecruitGuest() {
        AppearanceRecruit recruit = saveRecruit();
        AppearanceApplication first = saveApplication(recruit, 2L);
        first.withdraw(2L, NOW);
        appearanceApplicationRepository.saveAndFlush(first);

        assertThat(appearanceApplicationRepository.existsByRecruitIdAndGuestId(recruit.getId(), 2L)).isTrue();
        assertThat(appearanceApplicationRepository.existsByRecruitIdAndGuestId(recruit.getId(), 3L)).isFalse();
        // 제약 위반 뒤에는 같은 세션을 더 쓰지 않는다 (실패한 엔티티가 남아 있어서)
        assertThatThrownBy(() -> saveApplication(recruit, 2L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_appearance_applications_recruit_guest");
    }

    @Test
    @DisplayName("지원자 수는 취소(WITHDRAWN)만 빼고 센다. 여러 모집글을 한 번에 세면 지원 없는 모집글은 결과에 없다")
    void countApplicants() {
        AppearanceRecruit recruit = saveRecruit();
        AppearanceRecruit empty = saveRecruit();
        saveApplication(recruit, 2L);
        AppearanceApplication rejected = saveApplication(recruit, 3L);
        rejected.reject(NOW);
        appearanceApplicationRepository.saveAndFlush(rejected);
        AppearanceApplication withdrawn = saveApplication(recruit, 4L);
        withdrawn.withdraw(4L, NOW);
        appearanceApplicationRepository.saveAndFlush(withdrawn);

        assertThat(appearanceApplicationRepository.countByRecruitIdAndStatusIn(recruit.getId(),
                AppearanceApplicationStatus.COUNTED)).isEqualTo(2);
        Map<Long, Long> counts = appearanceApplicationRepository.countByRecruitIds(
                        List.of(recruit.getId(), empty.getId()), AppearanceApplicationStatus.COUNTED).stream()
                .collect(Collectors.toMap(RecruitApplicantCount::getRecruitId, RecruitApplicantCount::getApplicantCount));
        assertThat(counts).containsExactly(Map.entry(recruit.getId(), 2L));
    }

    @Test
    @DisplayName("모집글의 대기 중 지원만 모두 가져온다 (마감 정리용, 다른 모집글·응답된 지원 제외)")
    void findPendingByRecruit() {
        AppearanceRecruit recruit = saveRecruit();
        AppearanceRecruit other = saveRecruit();
        AppearanceApplication pending = saveApplication(recruit, 2L);
        AppearanceApplication selected = saveApplication(recruit, 3L);
        selected.select(NOW);
        appearanceApplicationRepository.saveAndFlush(selected);
        saveApplication(other, 2L);

        assertThat(appearanceApplicationRepository.findByRecruitIdAndStatus(recruit.getId(),
                AppearanceApplicationStatus.APPLIED)).extracting(AppearanceApplication::getId)
                .containsExactly(pending.getId());
    }

    @Test
    @DisplayName("모집글별·게스트별 목록을 상태로 거른다")
    void findByRecruitAndGuest() {
        AppearanceRecruit recruit = saveRecruit();
        AppearanceRecruit other = saveRecruit();
        AppearanceApplication mine = saveApplication(recruit, 2L);
        saveApplication(recruit, 3L);
        AppearanceApplication mineElsewhere = saveApplication(other, 2L);

        assertThat(appearanceApplicationRepository.findByRecruitIdAndStatusIn(recruit.getId(), ALL, FIRST_PAGE)
                .getTotalElements()).isEqualTo(2);
        assertThat(appearanceApplicationRepository.findByGuestIdAndStatusIn(2L, ALL, FIRST_PAGE).getContent())
                .extracting(AppearanceApplication::getId)
                .containsExactly(mineElsewhere.getId(), mine.getId());
        assertThat(appearanceApplicationRepository.findByGuestIdAndStatusIn(2L,
                List.of(AppearanceApplicationStatus.SELECTED), FIRST_PAGE).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("지원 하나에 출연 하나: 같은 지원으로 출연 두 번은 DB가 막고, 지원 ID로 출연을 찾는다")
    void appearanceByApplication() {
        AppearanceRecruit recruit = saveRecruit();
        AppearanceApplication application = saveApplication(recruit, 2L);
        application.select(NOW);
        appearanceApplicationRepository.saveAndFlush(application);

        Appearance appearance = appearanceRepository.saveAndFlush(Appearance.establishFrom(recruit, application, NOW));

        assertThat(appearanceRepository.findByApplicationIdIn(List.of(application.getId())))
                .extracting(Appearance::getId).containsExactly(appearance.getId());
        assertThat(appearance.getRecruitId()).isEqualTo(recruit.getId());
        assertThatThrownBy(() -> appearanceRepository.saveAndFlush(Appearance.establishFrom(recruit, application, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
