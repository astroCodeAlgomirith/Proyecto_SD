package com.escom.banco.server;

import com.escom.banco.json.Json;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /panel -> agrega el /stats de este nodo + el de los peers (fetch del lado
 * del servidor, sin CORS). Lo consume dashboard.html. Los peers son host:port
 * de los OTROS nodos (su puerto HTTP), inyectados para poder probarlo.
 */
public class PanelHandler implements HttpHandler {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final String idLocal;
    private final List<String> peers;

    public PanelHandler(String idLocal, List<String> peers) {
        this.idLocal = idLocal;
        this.peers = peers;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        List<Map<String, Object>> nodos = new ArrayList<>();
        nodos.add(conMeta(idLocal, true, StatsHandler.snapshot()));
        for (String peer : peers) nodos.add(consultarPeer(peer));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodos", nodos);
        HttpUtil.enviarJson(ex, 200, Json.toJson(out));
    }

    private Map<String, Object> consultarPeer(String hostPuerto) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://" + hostPuerto + "/stats"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            String cuerpo = http.send(req, BodyHandlers.ofString()).body();
            JsonObject j = Json.parse(cuerpo);
            Map<String, Object> m = Json.GSON.fromJson(j, LinkedHashMap.class);
            return conMeta(hostPuerto, true, m);
        } catch (Exception e) {
            return conMeta(hostPuerto, false, new LinkedHashMap<>());
        }
    }

    private static Map<String, Object> conMeta(String host, boolean alcanzable, Map<String, Object> stats) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("host", host);
        m.put("alcanzable", alcanzable);
        m.putAll(stats);
        return m;
    }
}
