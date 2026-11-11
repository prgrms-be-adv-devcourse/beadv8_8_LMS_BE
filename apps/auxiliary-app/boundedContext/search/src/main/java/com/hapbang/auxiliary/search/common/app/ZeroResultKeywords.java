package com.hapbang.auxiliary.search.common.app;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 필터 없이 검색어만으로 결과가 0건이던 검색어를 모아 주기마다 로그 한 줄로 남긴다(#771 Q9, Q18). 사전·동의어를 근거 있게 늘리려는 기록이다.
 * 검색어는 남의 이름·닉네임·연락처일 수 있어 아래 조건을 모두 지킨다({@code docs/dev/logging.md} "민감 정보"의 예외).
 * <ul>
 *     <li><b>서로 다른 검색자 {@value #MIN_SEARCHERS}명 이상이 찾은 말만</b> 남긴다. 한 사람만 입력한 말(이름 잘못 입력 등)은 로그에 나오지 않는다.
 *     검색자 키(회원 ID, 비회원이면 client IP 해시, L19)는 이 판단을 위해 메모리에서만 세고(검색어마다 {@value #MIN_SEARCHERS}명까지) 로그에 남기지
 *     않는다.</li>
 *     <li>이메일·URL·전화번호 모양, {@value #MAX_LENGTH}자를 넘는 말은 세지 않는다.</li>
 *     <li>누가 검색했는지 붙지 않게, 요청에서는 세기만 하고 로그는 배치 스레드가 찍는다(요청의 {@code userId}·{@code traceId}가 붙지 않음).</li>
 *     <li>검색어는 앞뒤·연속 공백을 정리하고 소문자로 바꿔 센다. 한 주기에 서로 다른 검색어는 {@value #MAX_KEYWORDS}개까지 세고 넘치면 수만 센다.
 *     로그에는 많이 나온 순서로 {@value #MAX_LOGGED}개까지 남긴다.</li>
 *     <li>pod마다 따로 센다. 앱이 내려가면 아직 찍지 않은 주기분은 사라진다(지표 {@code hapbang.search.zero.results}는 남는다).</li>
 * </ul>
 */
@Slf4j
@Component
public class ZeroResultKeywords {

    static final int MAX_KEYWORDS = 1_000;
    static final int MAX_LOGGED = 100;
    static final int MIN_SEARCHERS = 2;
    static final int MAX_LENGTH = 30;

    /** 연락처 모양: 이메일(@), URL(://, www.), 숫자 8개 이상(전화번호·계좌 등) */
    private static final Pattern CONTACT_LIKE = Pattern.compile("@|://|www\\.|(\\d[\\s-]*){8,}");

    /** 한 주기의 검색어별 기록. 0건 요청만 지나가므로 락 하나로 충분하다(주기 교체와 겹친 1건을 잃지 않게) */
    private final Map<String, Tally> tallies = new HashMap<>();
    private long dropped;

    /**
     * 결과가 0건이던 검색어 하나를 센다.
     *
     * @param searcher 검색자 키. 서로 다른 검색자 수를 세는 데만 쓴다(equals로 비교). 회원이면 회원 ID({@link Long}), 비회원이면 client IP 해시
     *                 ({@link String}, L19)를 넘긴다. 타입이 달라 회원 ID와 IP 해시가 같은 검색자로 섞이지 않는다
     */
    public void record(String keyword, Object searcher) {
        String normalized = keyword.strip().replaceAll("\\s+", " ").toLowerCase();
        if (normalized.isEmpty() || normalized.length() > MAX_LENGTH || CONTACT_LIKE.matcher(normalized).find()) {
            return;
        }
        synchronized (this) {
            Tally tally = tallies.get(normalized);
            if (tally == null) {
                if (tallies.size() >= MAX_KEYWORDS) {
                    dropped++;
                    return;
                }
                tally = new Tally();
                tallies.put(normalized, tally);
            }
            tally.add(searcher);
        }
    }

    /** 서로 다른 검색자 {@value #MIN_SEARCHERS}명 이상이 찾은 검색어를 로그 한 줄로 남기고 비운다. 없으면 남기지 않는다 */
    public Map<String, Long> flush() {
        Map<String, Long> shared;
        int distinct;
        long droppedCount;
        synchronized (this) {
            shared = tallies.entrySet().stream()
                    .filter(entry -> entry.getValue().searchers.size() >= MIN_SEARCHERS)
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().count));
            distinct = tallies.size();
            droppedCount = dropped;
            tallies.clear();
            dropped = 0;
        }
        Map<String, Long> top = shared.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_LOGGED)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        if (!top.isEmpty()) {
            log.info("여러 회원이 찾았지만 결과가 없던 검색어를 모았다 keywords={}, distinct={}, dropped={}", top, distinct,
                    droppedCount);
        }
        return top;
    }

    /** 검색어 하나의 횟수와 찾은 검색자(최대 {@value #MIN_SEARCHERS}명까지만 기억) */
    private static final class Tally {

        private final Set<Object> searchers = new HashSet<>();
        private long count;

        void add(Object searcher) {
            count++;
            if (searcher != null && searchers.size() < MIN_SEARCHERS) {
                searchers.add(searcher);
            }
        }
    }
}
