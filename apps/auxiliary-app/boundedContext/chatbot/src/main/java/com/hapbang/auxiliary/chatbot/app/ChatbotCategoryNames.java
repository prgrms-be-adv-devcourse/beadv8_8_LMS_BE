package com.hapbang.auxiliary.chatbot.app;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.out.MemberSyncSource;

import lombok.extern.slf4j.Slf4j;

/**
 * 콘텐츠 분야 코드 → 한글 이름 (#719). 회원 API에서 받아 {@code category-names-ttl}(기본 10분) 동안 들고 있는다(분야 표는 거의 바뀌지 않는다).
 * <ul>
 *     <li><b>표가 있을 때</b>: 다시 받는 것은 한 호출만 하고 잠금 없이 부른다. 그동안 다른 호출은 들고 있던 표를 쓴다
 *     (회원 API가 느려도 줄을 서지 않는다).</li>
 *     <li><b>표가 아직 없을 때(기동 직후)</b>: 한 호출이 받는 동안 색인 쪽 호출({@link #current()})은 기다린다(쓸 표가 없어 기다리는 것 말고는
 *     할 일이 없다). 기다리는 시간은 회원 API 호출 timeout을 넘지 않는다. 화면용 호출({@link #namesOf})은 기다리지 않고 코드를 그대로 보여 준다
 *     (회원 API가 죽어 있어도 추천 응답이 줄을 서지 않는다).</li>
 *     <li>받지 못하면 들고 있던 표를 쓰고 {@code category-names-retry}(기본 30초) 뒤 다시 받는다. 로그는 실패하기 시작할 때와 다시 받았을 때만 남긴다.</li>
 *     <li>한 번도 받지 못했으면 {@link #current()}는 {@link Unavailable} 예외다. 색인 처리기는 이름이 필요한 문서만 실패로 돌린다(마스킹 삭제·숨김은 이름 없이 한다).
 *     {@link #namesOf}는 코드를 그대로 보여 준다.</li>
 * </ul>
 * 이름에 {@code Chatbot}을 붙인 것은 검색 모듈의 같은 일을 하는 {@code ContentCategoryNames}와 빈 이름이 겹치지 않게 하려는 것이다
 * (한 앱 안의 모듈이라 빈 이름 공간을 같이 쓴다, 모듈 사이 코드 참조는 하지 않는다).
 */
@Slf4j
@Component
public class ChatbotCategoryNames {

    private final MemberSyncSource memberSource;
    private final ChatbotInfluencerRecommendProperties properties;
    private final Clock clock;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final ReentrantLock firstFetch = new ReentrantLock();
    private volatile @Nullable Map<String, String> names;
    private volatile @Nullable Instant nextFetchAt;
    private volatile @Nullable RuntimeException lastFailure;

    ChatbotCategoryNames(MemberSyncSource memberSource, ChatbotInfluencerRecommendProperties properties,
            @Qualifier(ChatbotConfig.CLOCK) Clock clock) {
        this.memberSource = memberSource;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 지금 분야 표(색인 쪽). 표가 아직 없으면 받는 중인 호출을 기다린다.
     *
     * @throws Unavailable 받지 못했고 들고 있던 표도 없을 때
     */
    public Map<String, String> current() {
        Map<String, String> table = table(true);
        if (table == null) {
            throw new Unavailable(lastFailure);
        }
        return table;
    }

    /** 코드들의 한글 이름(화면 쪽). 기다리지 않는다. 표에 없거나 표가 아직 없으면 코드 그대로 */
    public List<String> namesOf(List<String> codes) {
        Map<String, String> table = table(false);
        return namesOf(codes, table == null ? Map.of() : table);
    }

    // 표를 돌려준다. 다시 받을 때가 됐으면 받는다(표가 있으면 한 호출만, 없으면 잠금으로 한 호출만). wait=false면 잠금을 기다리지 않는다
    private @Nullable Map<String, String> table(boolean wait) {
        if (names != null) {
            if (due() && refreshing.compareAndSet(false, true)) {
                try {
                    fetch();
                } finally {
                    refreshing.set(false);
                }
            }
        } else if (wait ? lock() : firstFetch.tryLock()) {
            try {
                if (names == null && due()) {
                    fetch();
                }
            } finally {
                firstFetch.unlock();
            }
        }
        return names;
    }

    private boolean lock() {
        firstFetch.lock();
        return true;
    }

    /** 표로 코드들의 한글 이름을 찾는다. 표에 없는 코드(분야 표가 늦게 갱신됨)는 그대로 */
    static List<String> namesOf(List<String> codes, Map<String, String> table) {
        List<String> result = new ArrayList<>();
        for (String code : codes) {
            result.add(table.getOrDefault(code, code));
        }
        return result;
    }

    private boolean due() {
        Instant due = nextFetchAt;
        return due == null || !clock.instant().isBefore(due);
    }

    // 성공하면 TTL 뒤, 실패하면 retry 뒤에 다시 받는다. 상태가 바뀔 때만 로그를 남긴다
    private void fetch() {
        Instant now = clock.instant();
        try {
            Map<String, String> fetched = Map.copyOf(memberSource.categoryNames());
            // 시각을 표보다 먼저 쓴다: 표를 본 다른 호출이 아직 비어 있는 시각을 보고 다시 받지 않게
            nextFetchAt = now.plus(properties.categoryNamesTtl());
            names = fetched;
            if (lastFailure != null) {
                log.info("콘텐츠 분야 이름을 다시 받았다");
            }
            lastFailure = null;
        } catch (RuntimeException e) {
            nextFetchAt = now.plus(properties.categoryNamesRetry());
            if (lastFailure == null) {
                log.warn("콘텐츠 분야 이름을 받지 못했다. 들고 있던 표(없으면 코드)를 쓰고 retryAfter={} 뒤 다시 받는다",
                        properties.categoryNamesRetry(), e);
            }
            lastFailure = e;
        }
    }

    /** 분야 이름 표를 한 번도 받지 못했다(회원 API 장애). 이름이 필요한 일만 실패시킨다 */
    public static class Unavailable extends IllegalStateException {

        Unavailable(@Nullable Throwable cause) {
            super("콘텐츠 분야 이름을 받지 못했습니다", cause);
        }
    }
}
