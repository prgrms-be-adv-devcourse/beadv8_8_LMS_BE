package com.hapbang.auxiliary.chatbot.app;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.auxiliary.chatbot.domain.Faq;
import com.hapbang.auxiliary.chatbot.domain.FaqRepository;
import com.hapbang.auxiliary.chatbot.out.FaqVectorIndex;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 질문과 뜻이 가까운 FAQ를 찾는다(#715, 벡터 의미 검색). 결과의 질문·답변은 DB(원본)에서 읽고, 색인에만 남은 문서는 버린다.
 * <p>
 * 색인이 비었거나 ES·임베딩 모델이 죽으면 빈 목록이다. 대화는 그때 정해진 안내 문구로 끝난다.
 * 질문 본문은 로그에 남기지 않는다(docs/dev/logging.md). 장애 중에는 요청이 몰려 실패가 쏟아지므로 실패가 시작될 때 WARN(stack trace 포함),
 * 다시 될 때 INFO를 한 번씩 남기고 그 사이 실패는 DEBUG와 지표({@code faq.search.errors})로 본다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqSearchService {

    private final FaqVectorIndex vectorIndex;
    private final FaqRepository faqRepository;
    private final ChatbotMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean failing = new AtomicBoolean(false);

    // ES·임베딩 호출 중에는 DB 연결을 잡지 않는다. 결과를 찾은 뒤 짧은 트랜잭션 안에서 읽고 응답 값으로 바꾼다(docs/dev/persistence.md)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<FaqHit> search(String question) {
        List<FaqVectorIndex.ScoredCode> scored;
        try {
            scored = vectorIndex.search(question);
        } catch (RuntimeException e) {
            metrics.searchError();
            if (failing.compareAndSet(false, true)) {
                log.warn("FAQ를 검색하지 못해 빈 결과로 답하기 시작한다 index={}", vectorIndex.indexName(), e);
            } else {
                log.debug("FAQ를 검색하지 못해 빈 결과로 답한다 index={}, error={}", vectorIndex.indexName(), e.toString());
            }
            return List.of();
        }
        if (failing.compareAndSet(true, false)) {
            log.info("FAQ 검색이 다시 된다 index={}", vectorIndex.indexName());
        }
        if (scored.isEmpty()) {
            return List.of();
        }
        return transactionTemplate.execute(status -> toHits(scored));
    }

    private List<FaqHit> toHits(List<FaqVectorIndex.ScoredCode> scored) {
        Map<String, Faq> faqs = faqRepository.findByCodeIn(scored.stream().map(FaqVectorIndex.ScoredCode::code).toList())
                .stream().collect(Collectors.toMap(Faq::getCode, Function.identity()));
        return scored.stream()
                .map(hit -> {
                    Faq faq = faqs.get(hit.code());
                    return faq == null ? null : new FaqHit(faq.getCode(), faq.getQuestion(), faq.getAnswer(), hit.score());
                })
                .filter(Objects::nonNull)
                .toList();
    }
}
