package org.example.kipper_academy.api;

/** Resposta do {@code 202 Accepted} de {@code POST /api/events} (SPEC 7). */
public record EventAcceptedResponse(String eventId, String status) {

    public static EventAcceptedResponse received(String eventId) {
        return new EventAcceptedResponse(eventId, "RECEIVED");
    }
}
