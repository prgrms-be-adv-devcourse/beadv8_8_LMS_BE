package modulithfixture.beta;

import modulithfixture.alpha.AlphaService;

/** 허용하지 않은 모듈(alpha)을 참조하는 테스트용 모듈 beta. 검사가 이 참조를 잡아야 한다 */
public class BetaService {

    private final AlphaService alphaService = new AlphaService();

    public String name() {
        return "beta-" + alphaService.name();
    }
}
