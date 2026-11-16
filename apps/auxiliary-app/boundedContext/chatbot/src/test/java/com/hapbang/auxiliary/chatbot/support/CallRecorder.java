package com.hapbang.auxiliary.chatbot.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 호출 하나가 DB·ES를 몇 번 부르는지 모은다 (#835 효율 리팩터의 전후 비교). {@link CallRecorderConfig}를 @Import 한 컨텍스트에서만
 * 기록된다. {@link #record} 안에서 실행한 SQL과 ES 요청만 모은다(스레드와 상관없이 모은다. 기록 중에는 다른 작업을 돌리지 않는다).
 */
public final class CallRecorder {

    private static final AtomicReference<Calls> CURRENT = new AtomicReference<>();

    private CallRecorder() {
    }

    public static <T> Recorded<T> record(Supplier<T> call) {
        Calls calls = new Calls();
        CURRENT.set(calls);
        try {
            return new Recorded<>(call.get(), calls);
        } finally {
            CURRENT.set(null);
        }
    }

    static void sql(String sql) {
        Calls calls = CURRENT.get();
        if (calls != null) {
            calls.sql.add(sql);
        }
    }

    static void es(String request) {
        Calls calls = CURRENT.get();
        if (calls != null) {
            calls.es.add(request);
        }
    }

    public record Recorded<T>(T value, Calls calls) {
    }

    public static final class Calls {

        private final List<String> sql = new CopyOnWriteArrayList<>();
        private final List<String> es = new CopyOnWriteArrayList<>();

        /** 실행한 SQL(순서대로) */
        public List<String> sql() {
            return List.copyOf(sql);
        }

        /** {@code chatbot.<table>}을 읽거나 쓴 SQL 수 */
        public long sqlOn(String table) {
            Pattern pattern = Pattern.compile("chatbot\\." + table + "\\b");
            return sql.stream().filter(one -> pattern.matcher(one).find()).count();
        }

        /** ES 요청 종류(클라이언트 메서드 이름, 예: search·index·bulk)를 순서대로 */
        public List<String> es() {
            return List.copyOf(es);
        }

        @Override
        public String toString() {
            return "sql=" + sql.size() + " " + sql + "\nes=" + es;
        }
    }
}
