package com.hapbang.testsupport;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.TestComponent;

@TestComponent
@SpringBootApplication(scanBasePackages = "com.hapbang")
@AutoConfigurationPackage(basePackages = "com.hapbang")
public class ModuleTestConfiguration {
}
