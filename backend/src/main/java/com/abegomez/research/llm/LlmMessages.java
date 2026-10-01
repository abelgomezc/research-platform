package com.abegomez.research.llm;

import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Conversacion de entrada para una llamada al modelo. Se separa del modelo de
 * dominio para que el wrapper no dependa de como el agente construye el prompt.
 */
public record LlmMessages(String systemPrompt, List<Message> conversation) {

    public LlmMessages {
        conversation = conversation == null ? List.of() : List.copyOf(conversation);
    }

    public static LlmMessages single(String systemPrompt, String userPrompt) {
        return new LlmMessages(systemPrompt,
                List.of(new org.springframework.ai.chat.messages.UserMessage(userPrompt)));
    }

    public LlmMessages appendAssistant(String text) {
        return new LlmMessages(systemPrompt,
                java.util.stream.Stream.concat(conversation.stream(),
                        java.util.stream.Stream.of((Message) new AssistantMessage(text))).toList());
    }

    /**
     * Anade un turno de usuario.
     *
     * <p>Lo necesita el bucle de tool calling: el resultado de una herramienta se
     * devuelve al modelo como un nuevo turno de usuario, porque en un modelo sin
     * tool calling nativo no existe el turno de role "tool".
     */
    public LlmMessages appendUser(String text) {
        return new LlmMessages(systemPrompt,
                java.util.stream.Stream.concat(conversation.stream(),
                        java.util.stream.Stream.of((Message) new UserMessage(text))).toList());
    }
}
