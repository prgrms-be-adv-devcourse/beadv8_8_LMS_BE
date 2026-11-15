package com.hapbang.auxiliary.chatbot.domain;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * 한 요청의 취향 (#718): 신호, 색인된 신호 강의 벡터, 출처별 중심 벡터, 관심사 벡터. 질의 벡터와 고른 강의의 이유를 정한다.
 * <ul>
 *     <li>질의 벡터: 신호 강의 벡터의 가중 평균. 관심사가 함께 있으면 {@code (1−α)·신호 + α·관심사}, 하나만 있으면 그것.</li>
 *     <li>이유: 고른 강의 벡터가 출처별 중심 벡터(구매·장바구니)와 관심사 벡터 중 가장 가까운 쪽. 구매·장바구니면 그 출처의 신호 강의 중
 *     가장 가까운 강의를 기준 강의로 둔다. 순위와 상관없이 강의마다 정하므로, 순위를 바꾸는 후처리가 들어와도 그대로 쓸 수 있다.</li>
 * </ul>
 */
public final class LectureTaste {

    private final List<LectureSignals.Signal> signals;
    private final Map<Long, float[]> signalVectors;
    private final Map<LectureSignals.Source, float[]> centroids;
    private final float @Nullable [] interest;

    private LectureTaste(List<LectureSignals.Signal> signals, Map<Long, float[]> signalVectors,
            Map<LectureSignals.Source, float[]> centroids, float @Nullable [] interest) {
        this.signals = signals;
        this.signalVectors = signalVectors;
        this.centroids = centroids;
        this.interest = interest;
    }

    /**
     * @param signals       신호({@link LectureSignals#weigh})
     * @param signalVectors 신호 강의 ID → 벡터. 아직 색인 전인 강의는 없다
     * @param interest      관심사 벡터. 관심사가 없으면 null
     */
    public static LectureTaste of(List<LectureSignals.Signal> signals, Map<Long, float[]> signalVectors,
            float @Nullable [] interest) {
        Map<LectureSignals.Source, float[]> centroids = new EnumMap<>(LectureSignals.Source.class);
        for (LectureSignals.Source source : LectureSignals.Source.values()) {
            float[] centroid = TasteVectors.weightedAverage(signalVectors, weightsById(signals, source));
            if (centroid != null) {
                centroids.put(source, centroid);
            }
        }
        return new LectureTaste(List.copyOf(signals), Map.copyOf(signalVectors), centroids, interest);
    }

    /**
     * @param interestWeight α
     * @return 질의 벡터. 신호 강의가 하나도 색인되지 않았고 관심사도 없으면 null
     */
    public float @Nullable [] query(double interestWeight) {
        float[] signal = TasteVectors.weightedAverage(signalVectors, weightsById(signals, null));
        if (signal != null && interest != null) {
            return TasteVectors.mix(signal, interest, interestWeight);
        }
        return signal != null ? signal : interest;
    }

    /**
     * 고른 강의의 이유. 질의 벡터를 신호나 관심사로 만들었으므로 기준이 하나는 있다.
     *
     * @param vector 고른 강의의 벡터. 없으면(kNN 뒤에 문서가 사라짐) 거리 대신 색인된 신호 가중 합이 큰 출처로 정하고 기준 강의는 두지 않는다
     */
    public Reasoned reason(float @Nullable [] vector) {
        if (vector == null) {
            return heaviest();
        }
        LectureSignals.Source bestSource = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Map.Entry<LectureSignals.Source, float[]> centroid : centroids.entrySet()) {
            double score = TasteVectors.cosine(vector, centroid.getValue());
            if (score > bestScore) {
                bestScore = score;
                bestSource = centroid.getKey();
            }
        }
        if (bestSource == null || interest != null && TasteVectors.cosine(vector, interest) > bestScore) {
            return Reasoned.INTEREST;
        }
        return new Reasoned(bestSource.reason(), closestSignal(vector, bestSource));
    }

    // 중심 벡터가 있는 출처 중 색인된 신호 가중 합이 큰 쪽. 같으면 강한 출처(선언 순서). 없으면 관심사
    private Reasoned heaviest() {
        LectureSignals.Source heaviest = null;
        double heaviestWeight = Double.NEGATIVE_INFINITY;
        for (LectureSignals.Source source : centroids.keySet()) {
            double weight = 0;
            for (LectureSignals.Signal signal : signals) {
                if (signal.source() == source && signalVectors.containsKey(signal.lectureId())) {
                    weight += signal.weight();
                }
            }
            if (weight > heaviestWeight) {
                heaviestWeight = weight;
                heaviest = source;
            }
        }
        return heaviest == null ? Reasoned.INTEREST : new Reasoned(heaviest.reason(), null);
    }

    // 그 출처의 신호 강의 중 가장 가까운 강의
    private @Nullable Long closestSignal(float[] vector, LectureSignals.Source source) {
        Long closest = null;
        double closestScore = Double.NEGATIVE_INFINITY;
        for (LectureSignals.Signal signal : signals) {
            float[] signalVector = signalVectors.get(signal.lectureId());
            if (signal.source() != source || signalVector == null) {
                continue;
            }
            double score = TasteVectors.cosine(vector, signalVector);
            if (score > closestScore) {
                closestScore = score;
                closest = signal.lectureId();
            }
        }
        return closest;
    }

    // 강의 ID → 가중. source가 있으면 그 출처만
    private static Map<Long, Double> weightsById(List<LectureSignals.Signal> signals,
            LectureSignals.@Nullable Source source) {
        Map<Long, Double> weights = new LinkedHashMap<>();
        for (LectureSignals.Signal signal : signals) {
            if (source == null || signal.source() == source) {
                weights.put(signal.lectureId(), signal.weight());
            }
        }
        return weights;
    }

    /**
     * 고른 강의의 이유.
     *
     * @param basisLectureId 문장에 넣을 기준 신호 강의(구매·장바구니 이유일 때). 없으면 null
     */
    public record Reasoned(LectureRecommendReason reason, @Nullable Long basisLectureId) {

        public static final Reasoned POPULAR = new Reasoned(LectureRecommendReason.POPULAR, null);
        public static final Reasoned INTEREST = new Reasoned(LectureRecommendReason.MATCHES_INTEREST, null);
    }
}
