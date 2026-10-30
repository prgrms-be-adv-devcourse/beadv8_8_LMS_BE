package com.hapbang.chat.out.redis;

import static org.assertj.core.api.Assertions.*;

import java.net.ConnectException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandInterruptedException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisException;

/**
 * Redis 연결 불가(인프라)와 명령 오류(작업 실패로 셈)의 분류 (2026-10-30 사용자 결정).
 */
class RedisFailuresTest {

    @Test
    void 연결_거부_시간_초과_끊김_데이터_적재_중은_연결_불가다() {
        assertThat(RedisFailures.isConnectivityFailure(
                new RedisConnectionFailureException("Unable to connect", new ConnectException("refused")))).isTrue();
        assertThat(RedisFailures.isConnectivityFailure(
                new QueryTimeoutException("Redis command timed out", new RedisCommandTimeoutException()))).isTrue();
        assertThat(RedisFailures.isConnectivityFailure(new RedisSystemException("wrapped",
                new RedisConnectionException("Connection refused")))).isTrue();
        assertThat(RedisFailures.isConnectivityFailure(new RedisSystemException("wrapped",
                new RedisException("Connection closed")))).isTrue();
        assertThat(RedisFailures.isConnectivityFailure(new RedisSystemException("wrapped",
                new RedisCommandExecutionException("LOADING Redis is loading the dataset in memory")))).isTrue();
    }

    @Test
    void OOM_응답은_연결_불가가_아니라_메모리_상한이다() {
        RedisSystemException oom = new RedisSystemException("Error in execution",
                new RedisCommandExecutionException("OOM command not allowed when used memory > 'maxmemory'."));
        assertThat(RedisFailures.isMemoryFull(oom)).isTrue();
        assertThat(RedisFailures.isConnectivityFailure(oom)).isFalse();
        assertThat(RedisFailures.isMemoryFull(new RedisSystemException("Error in execution",
                new RedisCommandExecutionException("WRONGTYPE Operation against a key")))).isFalse();
    }

    @Test
    void 연결된_Redis가_거절한_명령_오류와_인터럽트는_연결_불가가_아니다() {
        assertThat(RedisFailures.isConnectivityFailure(new RedisSystemException("Error in execution",
                new RedisCommandExecutionException("OOM command not allowed when used memory > 'maxmemory'."))))
                .isFalse();
        assertThat(RedisFailures.isConnectivityFailure(new RedisSystemException("Error in execution",
                new RedisCommandExecutionException("WRONGTYPE Operation against a key holding the wrong kind"))))
                .isFalse();
        assertThat(RedisFailures.isConnectivityFailure(new RedisSystemException("Error in execution",
                new RedisCommandExecutionException("NOGROUP No such key or consumer group")))).isFalse();
        assertThat(RedisFailures.isConnectivityFailure(new RedisSystemException("interrupted",
                new RedisCommandInterruptedException(new InterruptedException())))).isFalse();
        assertThat(RedisFailures.isConnectivityFailure(new IllegalStateException("직렬화 실패"))).isFalse();
    }

    @Test
    void IO_예외와_I_O를_감싼_Lettuce_예외는_연결_불가다() {
        assertThat(RedisFailures.isConnectivityFailure(new RedisSystemException("wrapped",
                new RedisException("java.io.IOException: Broken pipe")))).isTrue();
        assertThat(RedisFailures.isConnectivityFailure(new RedisSystemException("wrapped",
                new RedisException(new java.io.IOException("Connection reset by peer"))))).isTrue();
    }

    @Test
    void 서버가_지금_명령을_받을_수_없다는_응답은_연결_불가다() {
        for (String reply : List.of("READONLY You can't write against a read only replica.",
                "MASTERDOWN Link with MASTER is down", "BUSY Redis is busy running a script.",
                "CLUSTERDOWN The cluster is down", "NOAUTH Authentication required.")) {
            assertThat(RedisFailures.isConnectivityFailure(new RedisSystemException("Error in execution",
                    new RedisCommandExecutionException(reply)))).as(reply).isTrue();
        }
        // BUSYGROUP은 BUSY가 아니다(이미 있는 그룹)
        assertThat(RedisFailures.isConnectivityFailure(new RedisSystemException("Error in execution",
                new RedisCommandExecutionException("BUSYGROUP Consumer Group name already exists")))).isFalse();
    }

    @Test
    void MISCONF는_OOM처럼_쓰기_거절이다() {
        RedisSystemException misconf = new RedisSystemException("Error in execution",
                new RedisCommandExecutionException("MISCONF Redis is configured to save RDB snapshots, but it's "
                        + "currently unable to persist to disk."));
        assertThat(RedisFailures.isWriteRejected(misconf)).isTrue();
        assertThat(RedisFailures.isMemoryFull(misconf)).isFalse();
        assertThat(RedisFailures.isConnectivityFailure(misconf)).isFalse();
    }

    @Test
    void 원인_사슬이_순환해도_끝난다() {
        CyclicException first = new CyclicException("첫째");
        CyclicException second = new CyclicException("둘째");
        CyclicException third = new CyclicException("셋째");
        first.next = second;
        second.next = third;
        third.next = first;

        assertThat(RedisFailures.isConnectivityFailure(first)).isFalse();
        assertThat(RedisFailures.isWriteRejected(first)).isFalse();
        assertThat(RedisFailures.hasMessage(first, "없음")).isFalse();
        assertThat(RedisFailures.hasMessage(first, "셋째")).isTrue();
    }

    /** getCause()가 순환하는 예외(initCause는 자기 자신만 막는다) */
    private static final class CyclicException extends RuntimeException {
        private Throwable next;

        CyclicException(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable getCause() {
            return next;
        }
    }
}
