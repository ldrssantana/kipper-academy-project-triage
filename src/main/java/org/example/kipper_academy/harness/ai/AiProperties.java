package org.example.kipper_academy.harness.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuracao do provedor de IA (prefixo {@code app.ai}).
 *
 * @param apiKey        chave da Anthropic (vem do .env, nunca versionada)
 * @param model         modelo usado pelo roteador e pelos especialistas
 * @param timeout       tempo maximo de cada chamada HTTP a API
 * @param maxToolRounds quantas vezes um especialista pode chamar ferramentas antes de o harness desistir
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        String apiKey,
        String model,
        Duration timeout,
        int maxToolRounds) {
}
