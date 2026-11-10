package com.hapbang.auxiliary.chatbot.domain;

/**
 * {@code chatbot.index_task.index_type}: 색인 요청의 대상 ES index (#717). DB에는 이름({@link #name()})을 적고,
 * 실행기(#718·#719)는 {@link #indexName()}으로 실제 index를 찾는다.
 */
public enum IndexType {

    /** 인플루언서 추천 index (#719) */
    INFLUENCER("chatbot-influencers");

    private final String indexName;

    IndexType(String indexName) {
        this.indexName = indexName;
    }

    /** ES index 이름 */
    public String indexName() {
        return indexName;
    }
}
