package com.hapbang.auxiliary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * auxiliary-app의 실행 진입점. 검색·챗봇·알림을 JVM 하나에 모은다(#684).
 * <p>
 * Spring Modulith는 이 클래스의 패키지({@code com.hapbang.auxiliary}) 바로 아래 패키지 중 {@code @ApplicationModule}을 붙인 것을
 * 모듈로 본다: 공통 {@code global}과 업무 모듈({@code chatbot} 등). 업무 모듈끼리의 참조는 {@code AuxiliaryModularityTest}가 막는다.
 * <p>
 * 다른 앱의 빈은 등록하지 않는다. shared의 공통 HTTP Client({@code com.hapbang.shared.*.out})는 스캔하지 않고,
 * shared의 엔티티 부모 타입만 JPA 패키지({@link AutoConfigurationPackage})로 쓴다.
 */
@Modulithic(sharedModules = "global")
@SpringBootApplication
@AutoConfigurationPackage(basePackages = {"com.hapbang.auxiliary", "com.hapbang.shared"})
public class AuxiliaryApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuxiliaryApplication.class, args);
    }
}
