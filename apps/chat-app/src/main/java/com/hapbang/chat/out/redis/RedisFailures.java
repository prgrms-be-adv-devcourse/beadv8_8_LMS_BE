package com.hapbang.chat.out.redis;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.PoolException;

import io.lettuce.core.RedisCommandInterruptedException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisException;

/**
 * Redis 예외를 연결 불가·쓰기 거절(인프라)과 명령 오류(작업 실패)로 나눈다 (2026-10-30 사용자 결정).
 * <p>
 * <b>연결 불가</b>({@link #isConnectivityFailure}, 원인 사슬 어디에든 있으면):
 * <ul>
 *     <li>Spring Data Redis {@link RedisConnectionFailureException}(연결 생성·거부), {@link QueryTimeoutException}
 *     (Lettuce 명령 시간 초과를 Spring이 바꾼 것), {@link PoolException}(풀에서 연결을 얻지 못함)</li>
 *     <li>Lettuce {@link RedisConnectionException}, {@link RedisCommandTimeoutException}, 연결이 끊겨 실패한
 *     {@link RedisException}("Connection closed", "Connection is closed", "Currently not connected", "Connection reset",
 *     I/O 예외를 감싼 "java.io.IOException: Broken pipe" 등)</li>
 *     <li>JDK {@link IOException}(소켓 오류·Broken pipe 등), {@link ClosedChannelException}</li>
 *     <li>서버가 지금 명령을 받을 수 없다는 오류 응답: {@code LOADING}(재시작 뒤 데이터 적재 중), {@code READONLY}(복제본),
 *     {@code MASTERDOWN}, {@code BUSY}(스크립트 실행 중, {@code BUSYGROUP}과 다르다), {@code CLUSTERDOWN},
 *     {@code NOAUTH}(인증 설정 불일치)</li>
 * </ul>
 * <b>쓰기 거절</b>({@link #isWriteRejected}): {@code OOM}(noeviction 메모리 상한), {@code MISCONF}(RDB 저장 실패로 쓰기 중단).
 * 연결은 정상이고 작업의 문제도 아니므로 적재를 멈추는 backpressure로 다룬다.
 * <p>
 * 그 밖에 서버가 명령을 거절한 오류 응답(WRONGTYPE·NOGROUP·BUSYGROUP·ERR·NOSCRIPT 등), 직렬화 오류는 작업 처리 실패로 센다.
 * 인터럽트({@link RedisCommandInterruptedException}, 종료 중 worker를 깨운 것)는 어느 쪽도 아니다. 원인 사슬은 순환을 막아
 * 한 번씩만 본다.
 */
final class RedisFailures {

    private static final int MAX_DEPTH = 32;
    private static final String[] DISCONNECTED_MESSAGES = {"Connection closed", "Connection is closed",
            "Currently not connected", "Connection reset", "Broken pipe", "java.io.IOException"};
    private static final String[] UNAVAILABLE_REPLIES = {"LOADING", "READONLY", "MASTERDOWN", "BUSY ", "CLUSTERDOWN",
            "NOAUTH"};
    private static final String[] WRITE_REJECTED_REPLIES = {"OOM ", "MISCONF "};

    private RedisFailures() {
    }

    static boolean isConnectivityFailure(Throwable error) {
        if (anyInChain(error, RedisCommandInterruptedException.class::isInstance)) {
            return false;
        }
        return anyInChain(error, current -> current instanceof RedisConnectionFailureException
                || current instanceof QueryTimeoutException || current instanceof PoolException
                || current instanceof RedisConnectionException || current instanceof RedisCommandTimeoutException
                || current instanceof IOException || current instanceof ClosedChannelException
                || startsWithAny(current.getMessage(), UNAVAILABLE_REPLIES)
                || (current instanceof RedisException && containsAny(current.getMessage(), DISCONNECTED_MESSAGES)));
    }

    /** 연결된 Redis가 쓰기를 거절했는지(OOM·MISCONF) */
    static boolean isWriteRejected(Throwable error) {
        return anyInChain(error, current -> startsWithAny(current.getMessage(), WRITE_REJECTED_REPLIES));
    }

    /** {@link #isWriteRejected} 중 메모리 상한(OOM)인지 */
    static boolean isMemoryFull(Throwable error) {
        return anyInChain(error, current -> startsWithAny(current.getMessage(), new String[] {"OOM "}));
    }

    static boolean hasMessage(Throwable error, String token) {
        return anyInChain(error, current -> current.getMessage() != null && current.getMessage().contains(token));
    }

    private static boolean anyInChain(Throwable error, Predicate<Throwable> matcher) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable current = error; current != null && depth < MAX_DEPTH && visited.add(current);
                current = current.getCause(), depth++) {
            if (matcher.test(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String message, String[] prefixes) {
        if (message == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (message.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String message, String[] tokens) {
        if (message == null) {
            return false;
        }
        for (String token : tokens) {
            if (message.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
