package com.hapbang.auxiliary.chatbot.app;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.out.MemberSyncSource;

import lombok.extern.slf4j.Slf4j;

/**
 * 콘텐츠 분야 코드 → 한글 이름 (#719). 회원 API에서 받아 {@code category-names-ttl}(기본 10분) 동안 들고 있는다(분야 표는 거의 바뀌지 않는다).
 * <ul>
 *     <li>다시 받지 못하면 들고 있던 표를 쓰고, TTL 동안 다시 부르지 않는다(회원 API 장애 때 요청마다 부르지 않는다).
 *     로그는 받기에 실패하기 시작할 때와 다시 받았을 때만 남긴다.</li>
 *     <li>한 번도 받지 못했으면 {@link #current()}는 예외다(색인 처리기는 실행기가 다시 시도한다). {@link #namesOf}는 코드를 그대로 보여 준다.</li>
 * </ul>
 */
@Slf4j
@Component
public class ContentCategoryNames {

    private final MemberSyncSource memberSource;
    private final ChatbotInfluencerRecommendProperties properties;
    private final Clock clock;
    private @Nullable Map<String, String> names;
    private @Nullable Instant nextFetchAt;
    private @Nullable RuntimeException lastFailure;

    ContentCategoryNames(MemberSyncSource memberSource, ChatbotInfluencerRecommendProperties properties,
            @Qualifier(ChatbotConfig.CLOCK) Clock clock) {
        this.memberSource = memberSource;
        this.properties = properties;
        this.clock = clock;
    }

    /** 지금 분야 표. 받지 못했고 들고 있던 표도 없으면 예외 */
    public synchronized Map<String, String> current() {
        Instant now = clock.instant();
        if (nextFetchAt == null || !now.isBefore(nextFetchAt)) {
            fetch(now);
        }
        if (names == null) {
            throw new IllegalStateException("콘텐츠 분야 이름을 받지 못했습니다", lastFailure);
        }
        return names;
    }

    /** 코드들의 한글 이름. 표에 없거나 표를 받을 수 없으면 코드 그대로 */
    public List<String> namesOf(List<String> codes) {
        Map<String, String> table;
        try {
            table = current();
        } catch (IllegalStateException e) {
            table = Map.of();
        }
        return namesOf(codes, table);
    }

    /** 표로 코드들의 한글 이름을 찾는다. 표에 없는 코드(분야 표가 늦게 갱신됨)는 그대로 */
    static List<String> namesOf(List<String> codes, Map<String, String> table) {
        List<String> result = new ArrayList<>();
        for (String code : codes) {
            result.add(table.getOrDefault(code, code));
        }
        return result;
    }

    // 성공·실패 모두 TTL 뒤에 다시 받는다. 상태가 바뀔 때만 로그를 남긴다
    private void fetch(Instant now) {
        nextFetchAt = now.plus(properties.categoryNamesTtl());
        try {
            names = Map.copyOf(memberSource.categoryNames());
            if (lastFailure != null) {
                log.info("콘텐츠 분야 이름을 다시 받았다");
            }
            lastFailure = null;
        } catch (RuntimeException e) {
            if (lastFailure == null) {
                log.warn("콘텐츠 분야 이름을 받지 못했다. 들고 있던 표(없으면 코드)를 쓰고 retryAfter={} 뒤 다시 받는다",
                        properties.categoryNamesTtl(), e);
            }
            lastFailure = e;
        }
    }
}
