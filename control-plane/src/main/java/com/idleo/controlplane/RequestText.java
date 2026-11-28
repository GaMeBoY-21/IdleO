package com.idleo.controlplane;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.KeyDeserializer;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.jdk.StringDeserializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * The text a JSON body may hold, decided once for every request: no NUL, which PostgreSQL can't store and would answer
 * with a 500, and no half of a UTF-16 surrogate pair, which can't be written as UTF-8 at all and would be stored as
 * {@code ?}. Every string and every map key goes through it, so a body holding either can't be read: the 400 {@code
 * malformed-request} of any unreadable body (ProblemDetailsAdvice), before a handler sees it.
 */
@Configuration(proxyBeanMethods = false)
class RequestText {

    /** Whether {@code text} is text the API takes. */
    static boolean readable(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u0000') {
                return false;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    i++;
                    continue;
                }
                return false;
            }
            if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    private static final String UNREADABLE = "holds a NUL or half of a surrogate pair";

    @Bean
    JacksonModule readableText() {
        var module = new SimpleModule("request-text");
        module.addDeserializer(String.class, new ValueDeserializer<String>() {
            @Override
            public String deserialize(JsonParser parser, DeserializationContext context) {
                var text = StringDeserializer.instance.deserialize(parser, context);
                if (text != null && !readable(text)) {
                    return (String) context.handleWeirdStringValue(String.class, text, UNREADABLE);
                }
                return text;
            }
        });
        module.addKeyDeserializer(String.class, new KeyDeserializer() {
            @Override
            public Object deserializeKey(String key, DeserializationContext context) {
                return readable(key) ? key : context.handleWeirdKey(String.class, key, UNREADABLE);
            }
        });
        return module;
    }
}
