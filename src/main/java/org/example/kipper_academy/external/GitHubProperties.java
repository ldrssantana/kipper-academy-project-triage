package org.example.kipper_academy.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Destino externo de teste (prefixo {@code app.github}).
 *
 * @param token              token fine-grained da conta bot (vem do .env, nunca versionado)
 * @param allowedRepository  UNICO repositorio onde o sistema pode comentar (dono/nome)
 * @param apiUrl             base da API REST do GitHub
 * @param timeout            tempo maximo de cada chamada
 */
@ConfigurationProperties(prefix = "app.github")
public record GitHubProperties(
        String token,
        String allowedRepository,
        String apiUrl,
        Duration timeout) {
}
