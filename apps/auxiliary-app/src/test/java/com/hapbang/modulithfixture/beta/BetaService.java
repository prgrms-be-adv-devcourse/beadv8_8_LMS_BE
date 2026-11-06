package com.hapbang.modulithfixture.beta;

import com.hapbang.modulithfixture.alpha.AlphaService;
import com.hapbang.modulithfixture.global.FixtureCommon;

/** 다른 업무 모듈(alpha)을 직접 참조한다. 경계 검사가 이 참조로 실패해야 한다 */
public class BetaService {

    private final AlphaService alpha = new AlphaService();

    FixtureCommon common() {
        return new FixtureCommon();
    }

    AlphaService alpha() {
        return alpha;
    }
}
