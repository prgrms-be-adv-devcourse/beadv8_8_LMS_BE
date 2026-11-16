package com.hapbang.auxiliary.chatbot.support;

import org.jspecify.annotations.Nullable;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.SqlProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.RequestBase;

/**
 * {@link CallRecorder}가 SQL·ES 요청을 세게 앱의 {@link NamedParameterJdbcTemplate}과 {@link ElasticsearchClient}를 감싼다.
 * 감싼 것은 받은 요청을 그대로 넘기므로 동작은 같다. 컨텍스트가 따로 뜨므로 호출 수를 보는 테스트만 @Import 한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CallRecorderConfig {

    @Bean
    static BeanPostProcessor callRecordingClients() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof NamedParameterJdbcTemplate named) {
                    return new NamedParameterJdbcTemplate(new RecordingJdbcTemplate(named.getJdbcTemplate()));
                }
                if (bean instanceof ElasticsearchClient client) {
                    return Mockito.mock(ElasticsearchClient.class, Mockito.withSettings().spiedInstance(client)
                            .defaultAnswer(CallRecorderConfig::recordEs));
                }
                return bean;
            }
        };
    }

    // 요청 객체를 받는 메서드만 센다(람다를 받는 메서드는 요청 객체를 만들어 같은 클라이언트의 메서드를 다시 부른다)
    private static @Nullable Object recordEs(InvocationOnMock invocation) throws Throwable {
        Object[] arguments = invocation.getArguments();
        if (arguments.length > 0 && arguments[0] instanceof RequestBase) {
            CallRecorder.es(invocation.getMethod().getName());
        }
        return invocation.callRealMethod();
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {

        RecordingJdbcTemplate(JdbcTemplate original) {
            super(original.getDataSource());
            setExceptionTranslator(original.getExceptionTranslator());
            setFetchSize(original.getFetchSize());
            setMaxRows(original.getMaxRows());
            setQueryTimeout(original.getQueryTimeout());
            setIgnoreWarnings(original.isIgnoreWarnings());
        }

        @Override
        public <T> T query(PreparedStatementCreator psc, @Nullable PreparedStatementSetter pss, ResultSetExtractor<T> rse) {
            CallRecorder.sql(sqlOf(psc));
            return super.query(psc, pss, rse);
        }

        @Override
        protected int update(PreparedStatementCreator psc, @Nullable PreparedStatementSetter pss) {
            CallRecorder.sql(sqlOf(psc));
            return super.update(psc, pss);
        }

        private static String sqlOf(PreparedStatementCreator psc) {
            return psc instanceof SqlProvider provider && provider.getSql() != null ? provider.getSql() : psc.toString();
        }
    }
}
