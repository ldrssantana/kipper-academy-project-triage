package org.example.kipper_academy.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.TreeMap;

/** Traduz erros da API para respostas RFC 9457 (ProblemDetail). */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Campo obrigatorio ausente ou invalido -> 400, listando cada campo (ex.: "data.title"). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new TreeMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Evento invalido.");
        problem.setProperty("errors", errors);
        return problem;
    }

    /** JSON malformado ou tipo errado (ex.: issueNumber "abc", data em formato invalido) -> 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Corpo da requisicao nao e um JSON valido para o contrato do evento.");
    }

    /**
     * Broker indisponivel ou sem confirmacao -> 503. O evento NAO foi aceito na fila, entao nao
     * respondemos 202; o cliente pode reenviar o mesmo eventId com seguranca.
     */
    @ExceptionHandler(AmqpException.class)
    ProblemDetail handleBrokerFailure(AmqpException ex) {
        log.error("Falha ao publicar evento na fila", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Fila indisponivel no momento; o evento nao foi aceito. Tente novamente.");
    }
}
