package com.hapbang.appearance.out;

import java.time.LocalDateTime;

import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.HpTaskStatus;

/**
 * 출연 수익 목록 한 줄 (#301): 출연과 그 출연의 지급·위약금 작업 상태(없으면 null), 상태 기준 시각.
 *
 * @param statusUpdatedAt 정렬과 응답에 함께 쓰는 값. 작업 완료 시각 → 확인 시각 → 취소 시각 → 불참 시각 → 출연 수정 시각 중 처음 있는 값
 */
public record AppearanceEarningRow(Appearance appearance, HpTaskStatus taskStatus, LocalDateTime statusUpdatedAt) {
}
