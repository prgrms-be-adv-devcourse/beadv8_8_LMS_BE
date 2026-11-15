package com.hapbang.auxiliary.notification.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

/**
 * B0 투입 명령({@code kafka-console-producer.sh})과 실행 결과 판정. b0.sh도 같은 인자와 같은 판정을 쓴다(loadtest/notification).
 * 종료 코드 0만으로 성공이라 하지 않는다: 적용된 producer 설정을 로그로 확인하고, 오류 줄이 하나라도 있으면 실패다.
 */
public final class B0ConsoleProducer {

    /** producer.properties 값이 실제 producer에 적용됐다는 로그 줄(ProducerConfig values) */
    public static final List<String> REQUIRED_CONFIG = List.of("acks = -1", "enable.idempotence = true",
            "max.in.flight.requests.per.connection = 1", "partitioner.ignore.keys = false",
            "partitioner.class = null");

    private B0ConsoleProducer() {
    }

    public static String command(String bootstrap, String topic, String producerConfig, String log4jConfig,
            String input) {
        return "KAFKA_LOG4J_OPTS='-Dlog4j2.configurationFile=" + log4jConfig + "' "
                + "/opt/kafka/bin/kafka-console-producer.sh --bootstrap-server " + bootstrap + " --topic " + topic
                + " --producer.config " + producerConfig
                + " --reader-property parse.key=true --reader-property parse.headers=true < " + input;
    }

    public static void assertConfigApplied(String stderr) {
        assertThat(stderr).as("ProducerConfig 로그").contains("ProducerConfig values");
        for (String line : REQUIRED_CONFIG) {
            assertThat(stderr).as(line).contains(line);
        }
    }

    /** ProducerConfig 로그를 뺀 오류·예외 줄 */
    public static List<String> errorLines(String stderr) {
        return Arrays.stream(stderr.split("\n"))
                .filter(line -> line.contains(" ERROR ") || line.contains("Exception"))
                .toList();
    }
}
