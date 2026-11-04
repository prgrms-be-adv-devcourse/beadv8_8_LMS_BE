package com.hapbang.chat.app;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.dto.ChatNotificationResult;
import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;

import lombok.RequiredArgsConstructor;

/**
 * 출연 이벤트 리스너가 알림을 남기는 진입점. 로그인 사용자가 없으므로 {@code @PreAuthorize}를 붙이지 않는다.
 * <p>
 * 같은 두 회원의 방을 동시에 만들거나(direct_key) 같은 알림을 동시에 남기면(알림 유일 인덱스) 한쪽이 유일 제약에 걸린다.
 * 새 트랜잭션에서 한 번 더 시도하면 먼저 만든 방을 재사용하거나, 이미 남긴 알림이라 건너뛴다.
 */
@Component
@RequiredArgsConstructor
public class ChatNotificationFacade {

    private final ChatNotificationUseCase chatNotificationUseCase;

    public Optional<ChatNotificationResult> notifyAppearanceRequestSent(AppearanceRequestDto request) {
        try {
            return chatNotificationUseCase.notifyAppearanceRequestSent(request);
        } catch (DataIntegrityViolationException conflict) {
            return chatNotificationUseCase.notifyAppearanceRequestSent(request);
        }
    }

    public Optional<ChatNotificationResult> notifyAppearanceApplicationSubmitted(
            AppearanceApplicationDto application) {
        try {
            return chatNotificationUseCase.notifyAppearanceApplicationSubmitted(application);
        } catch (DataIntegrityViolationException conflict) {
            return chatNotificationUseCase.notifyAppearanceApplicationSubmitted(application);
        }
    }
}
