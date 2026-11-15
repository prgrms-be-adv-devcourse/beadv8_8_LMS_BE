package com.hapbang.auxiliary.chatbot.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import com.hapbang.auxiliary.chatbot.app.FaqSyncService;
import com.hapbang.auxiliary.chatbot.config.ChatbotFaqProperties;
import com.hapbang.auxiliary.chatbot.out.FaqVectorIndex;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * FAQ 검색 평가(#715). 평가셋(chatbot/faq-eval.json)의 "다른 표현" 질문마다 정답 FAQ가 1위·3위 안에 드는지(hit@1·hit@3),
 * 범위 밖 질문을 기준값으로 거르는지 잰다. 실제 임베딩 모델을 부르므로 {@code @Tag("llm")}이고 CI에서 돌지 않는다.
 * <p>
 * 하위 클래스가 공급자 설정을 정한다. 검색은 기준값 0으로 하여 순위를 그대로 보고, 기준값 후보마다
 * "답할 수 있음(1위가 정답이고 기준값 이상)"과 "범위 밖 거절(1위 점수가 기준값 미만)" 비율을 표로 찍는다. 결과는 PR 본문·README에 옮긴다.
 * 품질 목표(hit@3)는 공급자마다 {@link #minHit3()}로 정한다. 운영 공급자만 목표를 걸고, 로컬 개발용 공급자는 표만 찍는다.
 */
abstract class FaqRetrievalEvaluation {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private FaqSyncService syncService;

    @Autowired
    private FaqVectorIndex vectorIndex;

    @Autowired
    private ChatbotFaqProperties properties;

    /** 표에 찍을 공급자·모델 이름 */
    abstract String modelLabel();

    /** 이 공급자가 넘어야 하는 hit@3. 0이면 목표 없이 표만 찍는다 */
    abstract double minHit3();

    @Test
    void evaluate() throws Exception {
        deleteIndex();
        syncService.syncAll();
        assertThat(vectorIndex.count()).isEqualTo(53);

        List<EvalCase> cases;
        try (InputStream in = new ClassPathResource("chatbot/faq-eval.json").getInputStream()) {
            cases = JSON.readValue(in, new TypeReference<List<EvalCase>>() {
            });
        }
        List<Result> inScope = new ArrayList<>();
        List<Result> outOfScope = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            List<FaqVectorIndex.ScoredCode> hits = vectorIndex.search(evalCase.question());
            Result result = new Result(evalCase, hits);
            (evalCase.expectedCode() == null ? outOfScope : inScope).add(result);
        }

        double hit1 = ratio(inScope.stream().filter(r -> r.rank() == 1).count(), inScope.size());
        double hit3 = ratio(inScope.stream().filter(r -> r.rank() >= 1 && r.rank() <= 3).count(), inScope.size());
        StringBuilder report = new StringBuilder();
        report.append("\n=== FAQ 검색 평가: ").append(modelLabel()).append(" (index ").append(properties.indexName())
                .append(", top ").append(properties.topK()).append(") ===\n");
        report.append(String.format(Locale.ROOT, "다른 표현 %d개: hit@1 %.3f, hit@3 %.3f%n", inScope.size(), hit1, hit3));
        report.append("기준값 | 답할 수 있음(1위 정답·기준값 이상) | 범위 밖 거절(1위 점수 < 기준값, ")
                .append(outOfScope.size()).append("개)\n");
        for (int i = 30; i <= 90; i += 2) {
            double threshold = i / 100.0;
            double answerable = ratio(inScope.stream().filter(r -> r.rank() == 1 && r.topScore() >= threshold).count(),
                    inScope.size());
            double rejected = ratio(outOfScope.stream().filter(r -> r.topScore() < threshold).count(), outOfScope.size());
            report.append(String.format(Locale.ROOT, "%.2f | %.3f | %.3f%n", threshold, answerable, rejected));
        }
        report.append("1위가 정답이 아닌 질문:\n");
        inScope.stream().filter(r -> r.rank() != 1).forEach(r -> report.append(String.format(Locale.ROOT,
                "  %s 기대 %s, 1위 %s(%.3f), 정답 순위 %s%n", r.evalCase().question(), r.evalCase().expectedCode(),
                r.topCode(), r.topScore(), r.rank() < 0 ? "3위 밖" : r.rank())));
        report.append("범위 밖 질문의 1위 점수:\n");
        outOfScope.forEach(r -> report.append(String.format(Locale.ROOT, "  %s → %s(%.3f)%n", r.evalCase().question(),
                r.topCode(), r.topScore())));
        System.out.println(report);
        deleteIndex();

        assertThat(inScope).hasSize(148);
        assertThat(outOfScope).hasSize(7);
        assertThat(hit3).as("%s hit@3", modelLabel()).isGreaterThanOrEqualTo(minHit3());
    }

    private void deleteIndex() throws Exception {
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create(ElasticsearchTestcontainersConfig.uri() + "/" + vectorIndex.indexName())).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private static double ratio(long count, int total) {
        return total == 0 ? 0 : (double) count / total;
    }

    record EvalCase(String question, String expectedCode) {
    }

    private record Result(EvalCase evalCase, List<FaqVectorIndex.ScoredCode> hits) {

        /** 정답의 순위(1부터). 결과 안에 없으면 -1 */
        int rank() {
            for (int i = 0; i < hits.size(); i++) {
                if (hits.get(i).code().equals(evalCase.expectedCode())) {
                    return i + 1;
                }
            }
            return -1;
        }

        double topScore() {
            return hits.isEmpty() ? 0 : hits.getFirst().score();
        }

        String topCode() {
            return hits.isEmpty() ? "-" : hits.getFirst().code();
        }
    }
}
