package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.app.dto.AppearanceRecruitResDto;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruit;

import lombok.RequiredArgsConstructor;

/**
 * 출연 모집글 기본 정보(제목·콘텐츠 설명·호스트 채널 URL) 수정. 트랜잭션은 호출한 {@link AppearanceRecruitFacade}가 연다.
 * <p>
 * 보낸 값으로 덮어쓰기만 하므로 같은 수정을 여러 번 해도 결과가 같다(멱등).
 * 같은 모집글을 동시에 바꾸면 버전(낙관적 락)이 맞지 않는 쪽을 {@code CONCURRENT_UPDATE}(409)로 막는다.
 * 커밋 때 나면 공통 핸들러가 500으로 응답하므로, 여기서 바로 반영(flush)해 업무 예외로 바꾼다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceRecruitUpdateUseCase {

    private final AppearanceRecruitSupport appearanceRecruitSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    private final AppearanceApplicationSupport appearanceApplicationSupport;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @throws AppearanceException 없거나 볼 수 없는 모집글, 호스트가 아님, 수정할 수 없는 상태, 입력 값 형식 오류
     */
    public AppearanceRecruitResDto updateBasicInfo(Long userId, Long recruitId, String title, String content,
                                                   String hostChannelUrl) {
        appearanceUserSupport.getParticipant(userId);
        AppearanceRecruit recruit = appearanceRecruitSupport.getById(recruitId);
        recruit.validateVisible();
        recruit.updateBasicInfo(userId, title, content, hostChannelUrl, LocalDateTime.now(clock));
        try {
            appearanceRecruitSupport.flush();
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new AppearanceException(AppearanceErrorCode.CONCURRENT_UPDATE);
        }
        return AppearanceRecruitResDto.from(recruit, appearanceApplicationSupport.countApplicants(recruitId));
    }
}
