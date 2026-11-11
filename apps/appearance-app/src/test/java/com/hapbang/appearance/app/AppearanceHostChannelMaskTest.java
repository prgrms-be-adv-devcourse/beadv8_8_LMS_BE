package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.appearance.domain.AppearanceBasicInfo;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.AppearanceRecruitRepository;
import com.hapbang.appearance.out.AppearanceRequestRepository;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.AppearanceTestClock;

/**
 * 탈퇴 회원 개인정보 파기 때 호스트 채널 URL 덮기 (#675). Kafka 수신은 {@code AppearanceUserEventListenerTest}가 본다.
 */
@AppearanceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@WithMockUser(roles = "INFLUENCER")
class AppearanceHostChannelMaskTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long OTHER_HOST = 3L;
    private static final String WITHDRAWN = AppearanceBasicInfo.WITHDRAWN_HOST_CHANNEL_URL;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceRequestRepository appearanceRequestRepository;

    @Autowired
    AppearanceRecruitRepository appearanceRecruitRepository;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        for (Long id : List.of(HOST, GUEST, OTHER_HOST)) {
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(id));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 준비

    /** 호스트가 보내고 바로 취소한 요청서 (끝난 요청서) */
    private Long canceledRequest(Long hostId) {
        Long id = appearanceRequestFacade.create(hostId, GUEST, AppearanceRequestFixture.content(clock.now())).id();
        appearanceRequestFacade.cancel(hostId, id);
        return id;
    }

    /** 응답 대기 중인 요청서 (진행 중) */
    private Long waitingRequest(Long hostId) {
        return appearanceRequestFacade.create(hostId, GUEST, AppearanceRequestFixture.content(clock.now())).id();
    }

    /** 올리고 바로 조기 마감한 모집글 (끝난 모집글) */
    private Long closedRecruit(Long hostId) {
        Long id = appearanceRecruitFacade.create(hostId, AppearanceRecruitFixture.content(clock.now())).id();
        appearanceRecruitFacade.closeEarly(hostId, id);
        return id;
    }

    /** 모집 중인 모집글 (진행 중) */
    private Long recruitingRecruit(Long hostId) {
        return appearanceRecruitFacade.create(hostId, AppearanceRecruitFixture.content(clock.now())).id();
    }

    private String requestChannel(Long id) {
        return appearanceRequestRepository.findById(id).map(AppearanceRequest::getHostChannelUrl).orElseThrow();
    }

    private String recruitChannel(Long id) {
        return appearanceRecruitRepository.findById(id).map(AppearanceRecruit::getHostChannelUrl).orElseThrow();
    }

    private AppearanceRequest request(Long id) {
        return appearanceRequestRepository.findById(id).orElseThrow();
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("파기하면 그 회원이 호스트인 끝난 요청서·모집글의 채널 URL이 withdrawn이 되고, 상태·버전은 그대로다")
    void masksFinished() {
        Long requestId = canceledRequest(HOST);
        Long recruitId = closedRecruit(HOST);
        AppearanceRequest before = request(requestId);

        appearanceUserFacade.maskWithdrawn(HOST);

        assertThat(requestChannel(requestId)).isEqualTo(WITHDRAWN);
        assertThat(recruitChannel(recruitId)).isEqualTo(WITHDRAWN);
        AppearanceRequest after = request(requestId);
        assertThat(after.getStatus()).isEqualTo(before.getStatus());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getTitle()).isEqualTo(before.getTitle());
        assertThat(after.getContent()).isEqualTo(before.getContent());
    }

    @Test
    @DisplayName("다른 회원이 호스트인 요청서·모집글은 그대로다")
    void othersUntouched() {
        Long otherRequestId = canceledRequest(OTHER_HOST);
        Long otherRecruitId = closedRecruit(OTHER_HOST);
        String original = requestChannel(otherRequestId);

        appearanceUserFacade.maskWithdrawn(HOST);

        assertThat(requestChannel(otherRequestId)).isEqualTo(original).startsWith("https://");
        assertThat(recruitChannel(otherRecruitId)).startsWith("https://");
    }

    @Test
    @DisplayName("같은 파기를 두 번 받아도 결과가 같다 (최소 한 번 전달)")
    void idempotent() {
        Long requestId = canceledRequest(HOST);
        Long recruitId = closedRecruit(HOST);

        appearanceUserFacade.maskWithdrawn(HOST);
        appearanceUserFacade.maskWithdrawn(HOST);

        assertThat(requestChannel(requestId)).isEqualTo(WITHDRAWN);
        assertThat(recruitChannel(recruitId)).isEqualTo(WITHDRAWN);
    }

    @Test
    @DisplayName("호스트로 쓴 글이 없거나 출연 회원이 없는 회원도 실패하지 않는다")
    void nothingToMask() {
        appearanceUserFacade.maskWithdrawn(HOST);
        appearanceUserFacade.maskWithdrawn(999L);

        assertThat(appearanceRequestRepository.count()).isZero();
    }

    @Test
    @DisplayName("진행 중인 요청서·모집글은 덮지 않고 error 로그를 남긴다 (탈퇴 확인에서 막혔어야 하는 비정상 상태), 끝난 글은 덮는다")
    void activeSkippedWithErrorLog(CapturedOutput output) {
        // 같은 게스트에게 응답 대기 요청이 있으면 새로 보낼 수 없으므로 끝난 요청서를 먼저 만든다
        Long canceledId = canceledRequest(HOST);
        Long waitingId = waitingRequest(HOST);
        Long recruitingId = recruitingRecruit(HOST);

        appearanceUserFacade.maskWithdrawn(HOST);

        assertThat(requestChannel(waitingId)).startsWith("https://");
        assertThat(recruitChannel(recruitingId)).startsWith("https://");
        assertThat(requestChannel(canceledId)).isEqualTo(WITHDRAWN);
        assertThat(output).contains("[HOST_CHANNEL_MASK_SKIPPED]")
                .contains("userId=1, activeRequests=1, activeRecruits=1");
    }

    @Test
    @DisplayName("덮은 뒤 상세 응답의 hostChannelUrl은 withdrawn이다 (FE 계약 값)")
    void detailShowsWithdrawn() {
        Long requestId = canceledRequest(HOST);

        appearanceUserFacade.maskWithdrawn(HOST);

        assertThat(appearanceRequestFacade.getDetail(GUEST, requestId).hostChannelUrl()).isEqualTo(WITHDRAWN);
    }
}
