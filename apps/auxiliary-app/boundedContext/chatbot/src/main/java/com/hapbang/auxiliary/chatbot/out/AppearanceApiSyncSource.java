package com.hapbang.auxiliary.chatbot.out;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import com.hapbang.shared.appearance.dto.AppearanceReviewSyncPageDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncPageDto;
import com.hapbang.shared.appearance.out.AppearanceApiClient;

/**
 * 출연 내부 동기화 API로 읽는다 (#716, 계약 docs/api/internal-appearance-sync.md).
 * <p>
 * auxiliary-app은 shared의 공통 Client 패키지를 스캔하지 않으므로({@code AuxiliaryApplication}) 출연 Client만 가져온다.
 * Client는 {@code hapbang.clients.appearance.base-url}이 있을 때만 생긴다. 운영 설정은 주소가 없으면 기동하지 않으므로
 * (application.yaml) Client가 없는 것은 주소 없이 띄운 모듈 테스트뿐이다. 그때 부르면 예외라 싱크 회차가 실패로 남는다.
 */
@Component
@Import(AppearanceApiClient.class)
public class AppearanceApiSyncSource implements AppearanceSyncSource {

    private final ObjectProvider<AppearanceApiClient> client;

    public AppearanceApiSyncSource(ObjectProvider<AppearanceApiClient> client) {
        this.client = client;
    }

    @Override
    public AppearanceSyncPageDto appearances(Long afterId, int size) {
        return client().getSyncAppearances(afterId, size);
    }

    @Override
    public AppearanceReviewSyncPageDto reviews(Long afterId, int size) {
        return client().getSyncReviews(afterId, size);
    }

    private AppearanceApiClient client() {
        AppearanceApiClient found = client.getIfAvailable();
        if (found == null) {
            throw new IllegalStateException("출연 내부 API Client가 없습니다(hapbang.clients.appearance.base-url 미설정).");
        }
        return found;
    }
}
