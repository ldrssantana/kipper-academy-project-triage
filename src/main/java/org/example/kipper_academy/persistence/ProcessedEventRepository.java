package org.example.kipper_academy.persistence;

import org.example.kipper_academy.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    /**
     * "Reivindica" o evento no ledger de idempotencia (SPEC 3.2 / 6.5).
     *
     * <p>INSERT nativo com ON CONFLICT, e nunca save(): save() faz merge e aceitaria em silencio
     * um eventId repetido. Deve rodar dentro da transacao que grava o resultado: se essa transacao
     * falhar, a reivindicacao e desfeita junto e o evento pode ser reprocessado.
     *
     * <p>Concorrencia: se dois consumidores recebem o mesmo eventId ao mesmo tempo, o segundo
     * INSERT espera o primeiro terminar; se o primeiro confirmou, o segundo recebe 0.
     *
     * @return 1 se este consumidor reivindicou o evento agora, 0 se ele ja tinha sido processado
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO processed_events (event_id, processed_at)
            VALUES (:eventId, :processedAt)
            ON CONFLICT (event_id) DO NOTHING
            """)
    int claim(@Param("eventId") String eventId, @Param("processedAt") Instant processedAt);
}
