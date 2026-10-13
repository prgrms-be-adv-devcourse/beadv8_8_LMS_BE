package com.hapbang.appearance.app;

import org.springframework.stereotype.Service;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.domain.AppearanceRequest;

import lombok.RequiredArgsConstructor;

/**
 * 출연 요청서 기본 정보(제목·콘텐츠 설명·호스트 채널 URL) 수정. 트랜잭션은 호출한 {@link AppearanceRequestFacade}가 연다.
 * <p>
 * 보낸 값으로 덮어쓰기만 하므로 같은 수정을 여러 번 해도 결과가 같다(멱등). 바뀐 값이 없으면 저장도 일어나지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceRequestUpdateUseCase {

    private final AppearanceRequestSupport appearanceRequestSupport;
    private final AppearanceUserSupport appearanceUserSupport;

    /**
     * @throws com.hapbang.appearance.domain.AppearanceException 없거나 볼 수 없는 요청서, 호스트가 아님, 수정할 수 없는 상태,
     *                                                           입력 값 형식 오류
     */
    public AppearanceRequestResDto updateBasicInfo(Long userId, Long requestId, String title, String content,
                                                   String hostChannelUrl) {
        appearanceUserSupport.getParticipant(userId);
        AppearanceRequest request = appearanceRequestSupport.getById(requestId);
        request.validateViewableBy(userId);
        request.updateBasicInfo(userId, title, content, hostChannelUrl);
        appearanceRequestSupport.flush();
        return AppearanceRequestResDto.from(request);
    }
}
