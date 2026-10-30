package com.hapbang.chat.in.event.kafka;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

/**
 * 리스너가 건너뛴 레코드(처리기가 없는 종류, 클래스패스에 없는 종류, tombstone)의 로그. 값은 남기지 않고 종류 이름과 위치만 남긴다.
 * <p>
 * 같은 종류가 계속 와도 넘치지 않게 종류마다 1·2·4·8…번째(2의 거듭제곱)만 INFO, 나머지는 DEBUG다. 따로 세는 종류는
 * {@value #MAX_COUNTERS}개까지이고(잘못 적은 클래스 이름도 건너뛰므로 메모리 상한을 둔다), 넘는 새 종류는 "기타 종류" 하나로 함께
 * 세며 그 로그에도 받은 종류 이름을 적는다. 계수는 메모리에만 있어 재시작하면 다시 센다.
 */
public final class SkippedEventLog {

    /** 따로 세는 최대 종류 수 */
    public static final int MAX_COUNTERS = 64;
    public static final String OVERFLOW_BUCKET = "기타 종류";

    private final Logger log;
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final AtomicLong overflow = new AtomicLong();

    public SkippedEventLog(Logger log) {
        this.log = log;
    }

    public void skipped(String type, String key, Integer partition, Long offset) {
        AtomicLong own = counterFor(type);
        long count = (own != null ? own : overflow).incrementAndGet();
        String bucket = own != null ? type : OVERFLOW_BUCKET;
        if (Long.bitCount(count) == 1) {
            log.info("처리하지 않는 이벤트 종류라 건너뜀 type={}, key={}, partition={}, offset={}, 누적={}, 집계={}", type, key,
                    partition, offset, count, bucket);
        } else {
            log.debug("처리하지 않는 이벤트 종류라 건너뜀 type={}, key={}, partition={}, offset={}, 누적={}, 집계={}", type, key,
                    partition, offset, count, bucket);
        }
    }

    /** 따로 세는 종류 수 */
    public int counterKeys() {
        return counters.size();
    }

    private AtomicLong counterFor(String type) {
        AtomicLong counter = counters.get(type);
        if (counter != null) {
            return counter;
        }
        synchronized (counters) {
            counter = counters.get(type);
            if (counter == null && counters.size() < MAX_COUNTERS) {
                counter = new AtomicLong();
                counters.put(type, counter);
            }
            return counter;
        }
    }
}
