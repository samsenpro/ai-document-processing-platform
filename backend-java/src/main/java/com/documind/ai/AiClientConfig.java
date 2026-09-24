package com.documind.ai;

import com.documind.auth.InternalApiKeyFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class AiClientConfig {

    /**
     * RestClient dedicado al servicio de IA, con timeouts propios: el procesamiento puede tardar
     * (OCR, LLM) pero nunca se espera indefinidamente.
     * <p>
     * Se fuerza HTTP/1.1: el cliente del JDK intenta por defecto un upgrade a h2c que uvicorn no
     * soporta y que algunos proxies cortan.
     */
    @Bean
    RestClient aiRestClient(RestClient.Builder builder, AiServiceProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder
                .baseUrl(properties.url())
                .requestFactory(requestFactory)
                .defaultHeader(InternalApiKeyFilter.HEADER, properties.apiKey())
                .build();
    }
}
