package com.hapbang.auxiliary.chatbot.app;

import java.util.Map;
import java.util.SequencedMap;

import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;

/**
 * 색인 요청 한 종류의 처리기(#718 공통 기반). 강의 index는 #718, 인플루언서 index는 #719가 구현해 빈으로 둔다.
 * 실행기({@link IndexTaskRunner})가 종류마다 하나를 찾아, 한 번 가져간 그 종류의 문서들을 한 번에 넘긴다. 종류마다 처리기는 하나만 있어야 한다.
 * <ul>
 *     <li><b>요청에 실린 값이 아니라 지금 사본 상태로 맞춘다.</b> 같은 문서에 쌓인 요청은 한 번에 넘기고, 늦게 실행돼도 결과가 같다.
 *     그래서 같은 문서를 여러 번 불러도 결과가 같아야 한다.</li>
 *     <li>여러 문서를 한 번에 받으므로 임베딩을 한 번의 공급자 호출로 묶을 수 있다({@code ChatbotEmbedder#embedDocuments}).</li>
 *     <li>실패한 문서는 돌려주는 맵에 원인과 함께 넣는다. 실행기가 그 문서만 간격을 늘려 다시 부른다.
 *     처리기 전체가 예외를 던지면 넘긴 문서가 모두 실패로 간다.</li>
 * </ul>
 */
public interface IndexTaskHandler {

    /** 맡은 종류 */
    IndexType type();

    /** 지금 처리할 수 있는지(임베딩 모델·ES 연결이 있다). false면 실행기가 이 종류의 요청을 가져가지 않고 남겨 둔다 */
    boolean available();

    /**
     * 문서들을 맞춘다.
     *
     * @param docs 문서 ID → 그 문서에 쌓인 요청 중 마지막 요청의 종류(오래된 순). 처리기는 사본 상태를 먼저 보고 종류는 참고로만 쓴다
     * @return 실패한 문서 ID → 원인. 모두 성공하면 빈 맵
     */
    Map<String, RuntimeException> handle(SequencedMap<String, IndexAction> docs);
}
