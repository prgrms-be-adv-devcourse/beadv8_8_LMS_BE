package com.hapbang.auxiliary.chatbot.app;

import java.util.List;

/** 취향 벡터 계산 (#719). 코사인 검색이라 섞기 전에 길이를 1로 맞춘다 */
final class Vectors {

    private Vectors() {
    }

    /** 길이 1로 맞춘다. 영벡터는 그대로 */
    static float[] normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0) {
            return vector.clone();
        }
        double length = Math.sqrt(norm);
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = (float) (vector[i] / length);
        }
        return result;
    }

    /** 같은 차원 벡터들의 평균 */
    static float[] mean(List<float[]> vectors) {
        float[] sum = new float[vectors.getFirst().length];
        for (float[] vector : vectors) {
            for (int i = 0; i < sum.length; i++) {
                sum[i] += vector[i];
            }
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] /= vectors.size();
        }
        return sum;
    }

    /** 내적(둘 다 길이 1이면 코사인 유사도) */
    static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /** {@code a·wa + b·wb} */
    static float[] weightedSum(float[] a, double wa, float[] b, double wb) {
        float[] result = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = (float) (a[i] * wa + b[i] * wb);
        }
        return result;
    }
}
