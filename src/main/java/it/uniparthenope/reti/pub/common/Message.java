package it.uniparthenope.reti.pub.common;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class Message {
    private final String command;
    private final Map<String, String> fields;

    private Message(String command, Map<String, String> fields) {
        this.command = command;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public static Builder builder(String command) {
        return new Builder(command);
    }

    public static Message parse(String line) {
        if (line == null) {
            throw new IllegalArgumentException("Messaggio vuoto");
        }

        String normalizedLine = line.trim();
        // Alcuni client esterni possono anteporre il BOM UTF-8 al primo messaggio.
        if (!normalizedLine.isEmpty() && normalizedLine.charAt(0) == '\uFEFF') {
            normalizedLine = normalizedLine.substring(1).trim();
        }

        if (normalizedLine.isEmpty()) {
            throw new IllegalArgumentException("Messaggio vuoto");
        }

        String[] tokens = normalizedLine.split("\\s+");
        Builder builder = builder(tokens[0]);

        for (int index = 1; index < tokens.length; index++) {
            String token = tokens[index];
            int separator = token.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Campo non valido: " + token);
            }

            String key = token.substring(0, separator);
            String value = decode(token.substring(separator + 1));
            builder.put(key, value);
        }

        return builder.build();
    }

    public String getCommand() {
        return command;
    }

    public String get(String key) {
        return fields.get(key);
    }

    public String require(String key) {
        String value = get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Campo obbligatorio mancante: " + key);
        }
        return value;
    }

    public int requireInt(String key) {
        try {
            return Integer.parseInt(require(key));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Campo numerico non valido: " + key);
        }
    }

    public String toLine() {
        StringBuilder builder = new StringBuilder(command);

        for (Map.Entry<String, String> field : fields.entrySet()) {
            builder.append(' ')
                    .append(field.getKey())
                    .append('=')
                    .append(encode(field.getValue()));
        }

        return builder.toString();
    }

    @Override
    public String toString() {
        return toLine();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException("Codifica UTF-8 non disponibile", ex);
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException("Codifica UTF-8 non disponibile", ex);
        }
    }

    public static final class Builder {
        private final String command;
        private final Map<String, String> fields = new LinkedHashMap<>();

        private Builder(String command) {
            if (command == null || command.trim().isEmpty()) {
                throw new IllegalArgumentException("Comando vuoto");
            }
            this.command = command.trim().toUpperCase(Locale.ROOT);
        }

        public Builder put(String key, Object value) {
            if (key == null || key.trim().isEmpty()) {
                throw new IllegalArgumentException("Chiave vuota");
            }
            fields.put(key.trim(), String.valueOf(value));
            return this;
        }

        public Message build() {
            return new Message(command, fields);
        }
    }
}
