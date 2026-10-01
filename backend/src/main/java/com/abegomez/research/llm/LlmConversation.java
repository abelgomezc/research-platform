package com.abegomez.research.llm;

import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Mensajes de una conversacion con el modelo. Usado por los agentes para
 * acumular el historial de una sesion de tool calling.
 */
public final class LlmConversation {

    private LlmConversation() {
    }

    public static List<Message> user(String text) {
        return List.of(new UserMessage(text));
    }

    public static Message assistant(String text) {
        return new AssistantMessage(text);
    }

    public static Message system(String text) {
        return new SystemMessage(text);
    }
}
