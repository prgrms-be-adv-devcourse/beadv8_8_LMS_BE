package com.hapbang.auxiliary.chatbot.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.auxiliary.chatbot.domain.Faq;
import com.hapbang.auxiliary.chatbot.domain.FaqRepository;
import com.hapbang.auxiliary.chatbot.out.FaqVectorIndex;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * DB의 FAQ를 벡터 색인에 맞춘다(#715).
 * <ol>
 *     <li>index가 없거나 비어 있으면 만들고 모든 FAQ를 다시 색인하게 한다(모델을 바꿔 index 이름이 바뀐 경우도 여기에 걸린다).</li>
 *     <li>색인에만 있는 code(시드에서 빠진 FAQ)를 지운다.</li>
 *     <li>내용 해시가 마지막 색인 해시와 다르거나 아직 색인하지 않은 FAQ만 임베딩해 넣는다.</li>
 *     <li>넣기에 성공한 뒤에만 {@code indexedHash}를 기록한다. 실패하면 그대로 두어 다음 점검·다음 기동에서 다시 한다.</li>
 * </ol>
 * 임베딩·ES 호출은 DB 트랜잭션 밖에서 한다. 호출 중 DB 연결을 잡고 있지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqIndexer {

    private final FaqRepository faqRepository;
    private final FaqVectorIndex vectorIndex;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * @return 결과. 색인을 쓸 수 없는 실행이면 {@code skipped}
     * @throws RuntimeException ES·임베딩 모델 호출이 실패했을 때. 이미 성공한 부분만 기록된다
     */
    public IndexResult sync() {
        if (!vectorIndex.available()) {
            return IndexResult.SKIPPED;
        }
        boolean rebuilt = vectorIndex.ensureIndex();
        if (rebuilt) {
            transactionTemplate.executeWithoutResult(status -> faqRepository.clearAllIndexed());
        }

        Set<String> known = transactionTemplate.execute(status -> faqRepository.findAll().stream()
                .map(Faq::getCode)
                .collect(Collectors.toSet()));
        Set<String> stale = new HashSet<>(vectorIndex.codes());
        stale.removeAll(known);
        vectorIndex.delete(stale);

        // 읽은 시점의 내용 해시를 함께 들고 간다. 색인 중에 시드가 바뀌면 그 해시만 기록되어 다음에 다시 색인된다
        List<Faq> pending = transactionTemplate.execute(status -> faqRepository.findNeedsIndexing());
        Map<Long, String> hashes = pending.stream().collect(Collectors.toMap(Faq::getId, Faq::getContentHash));
        vectorIndex.upsert(pending);
        LocalDateTime now = LocalDateTime.now(clock);
        transactionTemplate.executeWithoutResult(status -> faqRepository.findAllById(hashes.keySet())
                .forEach(faq -> faq.markIndexed(hashes.get(faq.getId()), now)));

        IndexResult result = new IndexResult(false, rebuilt, pending.size(), stale.size());
        log.info("FAQ 색인을 맞췄다 index={}, rebuilt={}, indexed={}, removed={}", vectorIndex.indexName(), rebuilt,
                result.indexed(), result.removed());
        return result;
    }

    /**
     * @param skipped 색인을 쓸 수 없는 실행이라 건너뜀
     * @param rebuilt index를 새로 만들었거나 비어 있어 모두 다시 색인함
     * @param indexed 임베딩해 넣은 FAQ 수
     * @param removed 색인에서 지운 문서 수
     */
    public record IndexResult(boolean skipped, boolean rebuilt, int indexed, int removed) {

        static final IndexResult SKIPPED = new IndexResult(true, false, 0, 0);
    }
}
