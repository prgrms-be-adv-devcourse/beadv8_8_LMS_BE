package com.hapbang.appearance.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.testsupport.RepositoryTest;

@RepositoryTest
class AppearanceRecruitRepositoryTest {

    private static final Long HOST = 1L;
    private static final Long OTHER = 3L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);
    private static final AppearancePolicy POLICY = new AppearancePolicy(20_000, 30, Duration.ofDays(7), 2, 10);
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id"));

    @Autowired
    AppearanceRecruitRepository appearanceRecruitRepository;

    /** 모집 중 모집글. 마감 일시는 기준 시각 + 2일 */
    private AppearanceRecruit saveRecruiting(Long hostId, LocalDateTime createdNow) {
        AppearanceRecruit recruit = AppearanceRecruit.create(hostId, AppearanceRecruitFixture.content(createdNow),
                POLICY, createdNow);
        recruit.markRecruiting();
        return appearanceRecruitRepository.saveAndFlush(recruit);
    }

    @Test
    @DisplayName("appearances.recruits에 저장하고 조회한다 (ID 자동 생성, 성립 인원 0, 버전·생성 시각 기록)")
    void saveAndFind() {
        AppearanceRecruit saved = saveRecruiting(HOST, NOW);

        AppearanceRecruit found = appearanceRecruitRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getId()).isPositive();
        assertThat(found.getStatus()).isEqualTo(AppearanceRecruitStatus.RECRUITING);
        assertThat(found.getFeePerPerson()).isEqualTo(AppearanceRecruitFixture.FEE_PER_PERSON);
        assertThat(found.getRecruitCount()).isEqualTo(AppearanceRecruitFixture.RECRUIT_COUNT);
        assertThat(found.getSelectedCount()).isZero();
        assertThat(found.getRecruitDeadline()).isEqualTo(NOW.plusDays(2));
        assertThat(found.getCloseType()).isNull();
        assertThat(found.getVersion()).isNotNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("공개 목록 조회는 모집 중이고 마감 일시가 기준 시각 이후인 모집글만 가져온다")
    void findOpen() {
        AppearanceRecruit open = saveRecruiting(HOST, NOW);
        saveRecruiting(OTHER, NOW.minusDays(3)); // 마감 일시(NOW - 1일)가 지났지만 아직 마감 처리 전
        AppearanceRecruit pending = AppearanceRecruit.create(HOST, AppearanceRecruitFixture.content(NOW), POLICY, NOW);
        appearanceRecruitRepository.saveAndFlush(pending);

        Page<AppearanceRecruit> found = appearanceRecruitRepository.findByStatusAndRecruitDeadlineAfter(
                AppearanceRecruitStatus.RECRUITING, NOW, FIRST_PAGE);

        assertThat(found.getContent()).extracting(AppearanceRecruit::getId).containsExactly(open.getId());
    }

    @Test
    @DisplayName("내 목록 조회는 호스트와 상태로 거른다")
    void findByHost() {
        AppearanceRecruit mine = saveRecruiting(HOST, NOW);
        saveRecruiting(OTHER, NOW);

        Page<AppearanceRecruit> found = appearanceRecruitRepository.findByHostIdAndStatusIn(HOST,
                List.of(AppearanceRecruitStatus.RECRUITING), FIRST_PAGE);
        Page<AppearanceRecruit> closedOnly = appearanceRecruitRepository.findByHostIdAndStatusIn(HOST,
                List.of(AppearanceRecruitStatus.CLOSED), FIRST_PAGE);

        assertThat(found.getContent()).extracting(AppearanceRecruit::getId).containsExactly(mine.getId());
        assertThat(closedOnly.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("배치 조회: 기준 시각 이전에 만든 보류 처리 중 모집글만")
    void findStaleHoldPending() {
        saveRecruiting(HOST, NOW);
        AppearanceRecruit pending = appearanceRecruitRepository.saveAndFlush(
                AppearanceRecruit.create(HOST, AppearanceRecruitFixture.content(NOW), POLICY, NOW));
        LocalDateTime createdAt = pending.getCreatedAt();

        assertThat(appearanceRecruitRepository.findIdsByStatusAndCreatedBefore(AppearanceRecruitStatus.HOLD_PENDING,
                createdAt.minusSeconds(1), FIRST_PAGE)).isEmpty();
        assertThat(appearanceRecruitRepository.findIdsByStatusAndCreatedBefore(AppearanceRecruitStatus.HOLD_PENDING,
                createdAt, FIRST_PAGE)).containsExactly(pending.getId());
    }
}
