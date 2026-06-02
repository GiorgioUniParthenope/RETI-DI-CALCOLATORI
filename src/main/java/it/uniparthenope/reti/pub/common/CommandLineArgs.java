package it.uniparthenope.reti.pub.common;

import java.util.HashMap;
import java.util.Map;

public final class CommandLineArgs {
    private final Map<String, String> values;

    private CommandLineArgs(Map<String, String> values) {
        this.values = values;
    }

    public static CommandLineArgs parse(String[] args) {
        Map<String, String> values = new HashMap<>();

        for (int index = 0; index < args.length; index++) {
            String token = args[index];
            if (!token.startsWith("--")) {
                continue;
            }

            String key = token.substring(2);
            String value = "true";
            if (index + 1 < args.length && !args[index + 1].startsWith("--")) {
                value = args[++index];
            }

            values.put(key, value);
        }

        return new CommandLineArgs(values);
    }

    public String get(String key, String defaultValue) {
        if (!values.containsKey(key)) {
            return defaultValue;
        }
        return values.get(key);
    }

    public int getInt(String key, int defaultValue) {
        if (!values.containsKey(key)) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(values.get(key));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }
}
