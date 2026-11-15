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
 * Client가 없을 때의 처리는 {@link ApiClients}.
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
        return ApiClients.require(client, "appearance");
    }
}
