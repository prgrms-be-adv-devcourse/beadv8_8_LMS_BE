package com.hapbang.auxiliary.chatbot.domain;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/** 취향 벡터 계산 (#718). 가중 평균·정규화·혼합·코사인. 벡터 차원은 모두 같다고 본다(임베딩이 확인한다) */
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
        float[] a = normalize(signal);
        float[] b = normalize(interest);
        float[] mixed = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            mixed[i] = (float) ((1 - interestWeight) * a[i] + interestWeight * b[i]);
        }
        return mixed;
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
