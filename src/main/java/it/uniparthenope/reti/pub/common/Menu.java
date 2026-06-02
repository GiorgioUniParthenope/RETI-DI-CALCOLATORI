package it.uniparthenope.reti.pub.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class Menu {
    private static final Map<String, String> ITEMS = createItems();

    private Menu() {
    }

    private static Map<String, String> createItems() {
        Map<String, String> items = new LinkedHashMap<>();
        items.put("PANINO", "Panino con hamburger");
        items.put("PIZZA", "Pizza margherita");
        items.put("PATATINE", "Patatine fritte");
        items.put("INSALATA", "Insalata mista");
        items.put("BIRRA", "Birra alla spina");
        return Collections.unmodifiableMap(items);
    }

    public static boolean contains(String itemCode) {
        return ITEMS.containsKey(normalize(itemCode));
    }

    public static String normalize(String itemCode) {
        if (itemCode == null) {
            return "";
        }
        return itemCode.trim().toUpperCase(Locale.ROOT);
    }

    public static String formatForProtocol() {
        StringBuilder builder = new StringBuilder();

        for (Map.Entry<String, String> item : ITEMS.entrySet()) {
            if (builder.length() > 0) {
                builder.append(";");
            }
            builder.append(item.getKey()).append(":").append(item.getValue());
        }

        return builder.toString();
    }

    public static String formatForConsole(String protocolMenu) {
        StringBuilder builder = new StringBuilder();
        String[] entries = protocolMenu.split(";");

        for (String entry : entries) {
            String[] parts = entry.split(":", 2);
            if (parts.length == 2) {
                builder.append(" - ")
                        .append(parts[0])
                        .append(": ")
                        .append(parts[1])
                        .append(System.lineSeparator());
            }
        }

        return builder.toString();
    }
}
