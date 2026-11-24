package org.example.kipper_academy.harness.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Cliente da API da Anthropic. So existe no processo worker (a ingestao nunca chama a IA). */
@Slf4j
@Configuration
@Profile("worker")
@EnableConfigurationProperties(AiProperties.class)
public class AnthropicConfig {

    @Bean(destroyMethod = "close")
    AnthropicClient anthropicClient(AiProperties props) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            // Nao derruba o worker: sem chave, toda chamada falha e o harness manda para revisao humana.
            log.warn("ANTHROPIC_API_KEY nao configurada: eventos que passarem pelas guardas iriam para REVISAO_HUMANA");
        }
        return AnthropicOkHttpClient.builder()
                .apiKey(props.apiKey() == null ? "" : props.apiKey())
                .timeout(props.timeout())
                .maxRetries(2) // 408/409/429/5xx e erros de conexao, com backoff do SDK
                .build();
    }
}
