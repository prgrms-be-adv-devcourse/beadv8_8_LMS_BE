package com.hapbang.auxiliary.chatbot.domain;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * 취향 벡터 계산 (#718, #719). 가중 평균·평균·정규화·혼합·내적·코사인. 강의·인플루언서 추천이 함께 쓴다.
 * 벡터 차원은 모두 같다고 본다(임베딩이 확인한다). 코사인 검색이라 섞기 전에 크기를 1로 맞춘다.
 */
public final class TasteVectors {

    private TasteVectors() {
    }

    /**
     * 가중 평균. 가중이 0 이하인 강의와 벡터가 없는 강의는 빠진다.
     *
     * @return 남은 가중 합이 0이면 null(신호 없음과 같다)
     */
    public static <K> float @Nullable [] weightedAverage(Map<K, float[]> vectors, Map<K, Double> weights) {
        double[] sum = null;
        double total = 0;
        for (Map.Entry<K, Double> entry : weights.entrySet()) {
            float[] vector = vectors.get(entry.getKey());
            double weight = entry.getValue();
            if (vector == null || weight <= 0) {
                continue;
            }
            if (sum == null) {
                sum = new double[vector.length];
            }
            for (int i = 0; i < vector.length; i++) {
                sum[i] += weight * vector[i];
            }
            total += weight;
        }
        if (sum == null || total <= 0) {
            return null;
        }
        float[] average = new float[sum.length];
        for (int i = 0; i < sum.length; i++) {
            average[i] = (float) (sum[i] / total);
        }
        return average;
    }

    /** 신호와 관심사를 섞는다: {@code (1−α)·정규화(신호) + α·정규화(관심사)} */
    public static float[] mix(float[] signal, float[] interest, double interestWeight) {
        return weightedSum(normalize(signal), 1 - interestWeight, normalize(interest), interestWeight);
    }

    /** {@code a·wa + b·wb} */
    public static float[] weightedSum(float[] a, double wa, float[] b, double wb) {
        float[] result = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = (float) (a[i] * wa + b[i] * wb);
        }
        return result;
    }

    /** 같은 차원 벡터들의 평균. 목록은 비어 있지 않아야 한다 */
    public static float[] mean(List<float[]> vectors) {
        double[] sum = new double[vectors.getFirst().length];
        for (float[] vector : vectors) {
            for (int i = 0; i < sum.length; i++) {
                sum[i] += vector[i];
            }
        }
        float[] mean = new float[sum.length];
        for (int i = 0; i < sum.length; i++) {
            mean[i] = (float) (sum[i] / vectors.size());
        }
        return mean;
    }

    /** 크기 1로 맞춘다. 0 벡터는 그대로 */
    public static float[] normalize(float[] vector) {
        double norm = norm(vector);
        if (norm == 0) {
            return vector.clone();
        }
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / norm);
        }
        return normalized;
    }

    /** 내적. 둘 다 크기 1이면 코사인 유사도다 */
    public static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    /** 코사인 유사도. 한쪽이 0 벡터면 0 */
    public static double cosine(float[] a, float[] b) {
        double normA = norm(a);
        double normB = norm(b);
        if (normA == 0 || normB == 0) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot / (normA * normB);
    }

    private static double norm(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        return Math.sqrt(sum);
    }
}
