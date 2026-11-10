package com.hapbang.auxiliary.search.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 검색 API 설정(#771): 순위 계수 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnSearchEnabled
@EnableConfigurationProperties(SearchRankingProperties.class)
public class SearchConfig {
}
