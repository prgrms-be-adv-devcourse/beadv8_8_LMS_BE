package com.hapbang.auxiliary.chatbot.app;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.out.MemberSyncSource;

import lombok.extern.slf4j.Slf4j;

/**
 * 콘텐츠 분야 코드 → 한글 이름 (#719). 회원 API에서 받아 잠시 들고 있는다(분야 표는 거의 바뀌지 않는다).
 * <ul>
 *     <li>{@value #TTL_MINUTES}분이 지나면 다시 받는다. 다시 받지 못하면 들고 있던 표를 쓰고 WARN을 남긴다.</li>
 *     <li>한 번도 받지 못했으면 예외다(색인 처리기는 다시 시도하고, 추천은 이름 대신 코드를 보여 준다).</li>
 * </ul>
 */
@Slf4j
@Component
public class ContentCategoryNames {

    static final long TTL_MINUTES = 10;
    private static final Duration TTL = Duration.ofMinutes(TTL_MINUTES);

    private final MemberSyncSource memberSource;
    private volatile @Nullable Map<String, String> names;
    private volatile long fetchedAtNanos;

    ContentCategoryNames(MemberSyncSource memberSource) {
        this.memberSource = memberSource;
    }

    /** 지금 분야 표. 받지 못했고 들고 있던 표도 없으면 예외 */
    public Map<String, String> current() {
        Map<String, String> cached = names;
        if (cached != null && System.nanoTime() - fetchedAtNanos < TTL.toNanos()) {
            return cached;
        }
        try {
            Map<String, String> fetched = Map.copyOf(memberSource.categoryNames());
            names = fetched;
            fetchedAtNanos = System.nanoTime();
            return fetched;
        } catch (RuntimeException e) {
            if (cached == null) {
                throw e;
            }
            log.warn("콘텐츠 분야 이름을 다시 받지 못해 들고 있던 표를 쓴다", e);
            return cached;
        }
    }

    /** 코드들의 한글 이름. 표에 없거나 표를 받을 수 없으면 코드 그대로 */
    public List<String> namesOf(List<String> codes) {
        Map<String, String> table;
        try {
            table = current();
        } catch (RuntimeException e) {
            log.warn("콘텐츠 분야 이름을 받지 못해 코드를 그대로 보여 준다", e);
            table = Map.of();
        }
        List<String> result = new ArrayList<>();
        for (String code : codes) {
            result.add(table.getOrDefault(code, code));
        }
        return result;
    }

    /** 들고 있던 표를 버린다(테스트가 분야 표를 바꾼 뒤 부른다) */
    void forget() {
        names = null;
    }
}
