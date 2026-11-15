package com.hapbang.testsupport;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.core.CreateContainerCmdModifier;

/** 로컬 Docker의 기본 포트 바인딩이 불가능할 때만 명시한 주소로 테스트 포트를 게시한다. */
public class TestContainerBindAddress implements CreateContainerCmdModifier {
    @Override
    public CreateContainerCmd modify(CreateContainerCmd command) {
        String address = System.getenv("HAPBANG_TEST_BIND_ADDRESS");
        if (address == null || address.isBlank() || command.getHostConfig() == null
                || command.getHostConfig().getPortBindings() == null) return command;
        Ports ports = new Ports();
        command.getHostConfig().getPortBindings().getBindings().forEach((port, bindings) -> {
            if (bindings != null) for (Ports.Binding binding : bindings) {
                ports.bind(port, new Ports.Binding(address, binding.getHostPortSpec()));
            }
        });
        command.getHostConfig().withPortBindings(ports);
        return command;
    }
}
