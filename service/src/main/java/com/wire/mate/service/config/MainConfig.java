package com.wire.mate.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/**
 * Application-wide infrastructure beans.
 *
 * <p>The {@link com.fasterxml.jackson.databind.ObjectMapper} is intentionally
 * <em>not</em> defined here — Spring Boot auto-configures one with all the
 * registered modules (notably {@code JavaTimeModule} for {@code Instant}
 * serialization) and re-defining it would silently strip those.</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(WireMateProperties.class)
public class MainConfig {

    /**
     * Dedicated {@link RestClient} for talking to WireMock.
     *
     * <p>Both connect and read timeouts are set explicitly — the default
     * {@code RestClient} has no timeouts and will hang the calling thread
     * forever if WireMock becomes unresponsive.</p>
     */
    @Bean
    public RestClient wiremockRestClient(WireMateProperties properties) {
        WireMateProperties.WireMock cfg = properties.wiremock();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) cfg.connectTimeout().toMillis());
        factory.setReadTimeout((int) cfg.readTimeout().toMillis());

        return RestClient.builder()
                .baseUrl(cfg.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
