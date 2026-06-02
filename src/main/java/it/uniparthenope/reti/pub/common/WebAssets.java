package it.uniparthenope.reti.pub.common;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class WebAssets {
    public static final String HERO_PATH = "/assets/pub-hero.png";

    private WebAssets() {
    }

    public static String faviconDataUri() {
        return "data:image/svg+xml,"
                + "%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E"
                + "%3Crect width='64' height='64' rx='12' fill='%23283b32'/%3E"
                + "%3Cpath d='M18 30h24v8a10 10 0 0 1-10 10h-4a10 10 0 0 1-10-10z' fill='%23f8efe0'/%3E"
                + "%3Cpath d='M42 32h6a5 5 0 0 1 0 10h-6' fill='none' stroke='%23f8efe0' stroke-width='5'/%3E"
                + "%3Cpath d='M21 24c3-5 7-5 10 0 3-5 7-5 10 0' fill='none' stroke='%23ffbf69' stroke-width='5' stroke-linecap='round'/%3E"
                + "%3C/svg%3E";
    }

    public static void sendHeroImage(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        Path imagePath = Paths.get("assets", "pub-hero.png");
        if (!Files.exists(imagePath)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        byte[] bytes = Files.readAllBytes(imagePath);
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
