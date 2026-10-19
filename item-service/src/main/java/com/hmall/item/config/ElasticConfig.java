package com.hmall.item.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch 客户端配置。
 * host 支持 ip:port，如 127.0.0.1:9200（本地直连容器映射端口）
 * 或 es:9200（服务与 ES 同 docker 网络时用容器名）。
 */
@Configuration
public class ElasticConfig {

    @Value("${hm.es.host:127.0.0.1:9200}")
    private String esHost;

    @Bean(destroyMethod = "close")
    public RestHighLevelClient restHighLevelClient() {
        String[] parts = esHost.split(":");
        return new RestHighLevelClient(RestClient.builder(
                new HttpHost(parts[0], Integer.parseInt(parts[1]), "http")));
    }
}
