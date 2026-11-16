package com.hapbang.auxiliary.notification.loadtest;

/**
 * L4 밀린 이벤트 회복 fixture(N15, NT-LOAD-L4, ND-189·ND-190). B0 생성기 규칙(카탈로그로 기대값 계산)을 그대로 쓰고 크기와 seed만 다르다.
 * <ul>
 *     <li>정상 백로그 10,000건: B0 정상 단계 4,925건의 분류별 비율을 두 배로 하고, 남는 150건은 가장 큰 분류인 충전 완료에 더했다.
 *     LEGACY·재전송(같은 사건)·받을 수 없는 회원(정지·탈퇴)처럼 소비가 정상으로 끝나는 건너뛰기를 포함한다.</li>
 *     <li>실패 단계 150건: 받는 사람 복제본 없음 100(재시도 뒤 DLT, ND-035)과 깨진 본문 50(바로 DLT). 정상 회복 뒤 따로 넣는다.</li>
 * </ul>
 */
public final class L4Fixtures {

    /** L4 seed. B0(20261114)와 다르다 */
    public static final long SEED = 20261116L;

    public static final B0FixtureGenerator.Sizes SIZES = new B0FixtureGenerator.Sizes(2_370, 740, 740, 1_200, 300,
            400, 600, 276, 800, 300, 750, 462, 462, 500, 100, 100, 50);

    private L4Fixtures() {
    }

    public static B0Fixture generate() {
        return B0FixtureGenerator.generate(SEED, SIZES);
    }
}
