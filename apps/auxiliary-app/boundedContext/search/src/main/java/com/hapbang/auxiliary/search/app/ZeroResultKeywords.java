package com.hapbang.auxiliary.search.app;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;

import lombok.extern.slf4j.Slf4j;

/**
 * 결과가 0건이던 검색어를 모아 주기마다 로그 한 줄로 남긴다(#771 Q9). 사전·동의어를 근거 있게 늘리려는 기록이다.
 * <ul>
 *     <li><b>누가 검색했는지는 남기지 않는다.</b> 요청 스레드에서 찍으면 MDC의 {@code userId}·{@code traceId}가 붙어 개인 검색 기록이 되므로,
 *     요청에서는 메모리에 세기만 하고 로그는 배치 스레드가 찍는다.</li>
 *     <li>검색어는 앞뒤 공백·연속 공백을 정리하고 소문자로 바꿔 센다. 한 주기에 서로 다른 검색어는 {@value #MAX_KEYWORDS}개까지 세고 넘치면 수만 센다.
 *     로그에는 많이 나온 순서로 {@value #MAX_LOGGED}개까지 남긴다.</li>
 *     <li>pod마다 따로 센다. 앱이 내려가면 아직 찍지 않은 주기분은 사라진다(지표 {@code hapbang.search.zero.results}는 남는다).</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnSearchEnabled
public class ZeroResultKeywords {

    static final int MAX_KEYWORDS = 1_000;
    static final int MAX_LOGGED = 100;

    /** 한 주기의 검색어별 횟수. 0건 요청만 지나가므로 락 하나로 충분하다(주기 교체와 겹친 1건을 잃지 않게) */
    private final Map<String, Long> counts = new HashMap<>();
    private long dropped;

    /** 결과가 0건이던 검색어 하나를 센다 */
    public void record(String keyword) {
        String normalized = keyword.strip().replaceAll("\\s+", " ").toLowerCase();
        synchronized (this) {
            if (!counts.containsKey(normalized) && counts.size() >= MAX_KEYWORDS) {
                dropped++;
                return;
            }
            counts.merge(normalized, 1L, Long::sum);
        }
    }

    /** 모은 검색어를 로그 한 줄로 남기고 비운다. 없으면 남기지 않는다 */
    public Map<String, Long> flush() {
        Map<String, Long> collected;
        long droppedCount;
        synchronized (this) {
            collected = Map.copyOf(counts);
            droppedCount = dropped;
            counts.clear();
            dropped = 0;
        }
        Map<String, Long> top = collected.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_LOGGED)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        if (!top.isEmpty() || droppedCount > 0) {
            log.info("결과가 없던 검색어를 모았다 keywords={}, distinct={}, dropped={}", top, collected.size(), droppedCount);
        }
        return top;
    }
}
