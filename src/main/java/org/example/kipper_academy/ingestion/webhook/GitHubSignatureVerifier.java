package org.example.kipper_academy.ingestion.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Confere que o webhook veio mesmo do GitHub: o GitHub assina o corpo com HMAC-SHA256 usando o
 * segredo cadastrado no webhook e envia em {@code X-Hub-Signature-256: sha256=<hex>}.
 * Sem segredo configurado, TODO webhook e recusado (falha fechada).
 */
@Component
@Profile("ingestion")
public class GitHubSignatureVerifier {

    private static final String PREFIX = "sha256=";

    private final byte[] secret;

    public GitHubSignatureVerifier(@Value("${app.github.webhook-secret:}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isConfigured() {
        return secret.length > 0;
    }

    public boolean isValid(byte[] body, String signatureHeader) {
        if (!isConfigured() || signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        byte[] expected = HexFormat.of().formatHex(hmac(body)).getBytes(StandardCharsets.US_ASCII);
        byte[] received = signatureHeader.substring(PREFIX.length()).getBytes(StandardCharsets.US_ASCII);
        // Comparacao em tempo constante: nao vaza, pelo tempo de resposta, quantos bytes acertou.
        return MessageDigest.isEqual(expected, received);
    }

    byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 indisponivel", e);
        }
    }
}
