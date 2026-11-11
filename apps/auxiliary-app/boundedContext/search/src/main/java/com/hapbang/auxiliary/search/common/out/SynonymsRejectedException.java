package com.hapbang.auxiliary.search.common.out;

/**
 * ES가 동의어 묶음을 <b>내용 때문에</b> 거절했다(4xx, 레포 {@code synonyms.txt}의 규칙이 틀림). 기다려도 풀리지 않아 사람이 파일을 고쳐야 한다
 * (PR #845 재리뷰). ES에 닿지 못하거나 5xx·429면 이 예외가 아니라 {@link IllegalStateException}이다(기다리면 풀린다).
 * <p>
 * 잘못된 규칙은 대개 CI의 {@code InfluencerIndexSetupTest}(실제 ES 9.5.5에 레포 파일을 올려 봄)가 병합 전에 잡는다.
 */
public class SynonymsRejectedException extends IllegalStateException {

    public SynonymsRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
