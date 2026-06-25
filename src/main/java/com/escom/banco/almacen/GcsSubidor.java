package com.escom.banco.almacen;

import com.escom.banco.json.Json;
import com.escom.banco.model.Transaccion;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Subidor real a Cloud Storage usando solo el JDK (sin la libreria de Google):
 * token OAuth de la metadata server de la VM + API JSON de GCS. GCS no permite
 * append, asi que cada PUT crea un objeto: el hot path usa subirLote(), que sube
 * muchas tx como un solo array transactions/batch-{min}-{max}.json (amortiza el
 * round-trip). subir() (un objeto por tx, transactions/{seq}.json) queda para
 * usos sueltos; la recuperacion lee ambos formatos.
 */
public final class GcsSubidor implements AlmacenGcs.Subidor {

    private static final String META_TOKEN =
            "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token";

    /** Intentos por objeto al descargar el log durante la recuperacion. */
    private static final int INTENTOS_DESCARGA = 3;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String bucket;

    private volatile String token;
    private volatile long tokenVenceMs = 0;

    public GcsSubidor(String bucket) { this.bucket = bucket; }

    @Override
    public boolean subir(Transaccion t) throws Exception {
        String nombre = URLEncoder.encode("transactions/" + t.seq() + ".json", StandardCharsets.UTF_8);
        String url = "https://storage.googleapis.com/upload/storage/v1/b/" + bucket
                + "/o?uploadType=media&name=" + nombre;
        String cuerpo = Json.toJson(t);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + obtenerToken())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build();
        int sc = http.send(req, BodyHandlers.discarding()).statusCode();
        return sc >= 200 && sc < 300;
    }

    /**
     * Sube el lote como UN solo objeto: un array JSON en
     * transactions/batch-{min}-{max}.json. El rango del nombre es informativo (la
     * recuperacion reordena por seq); el lote llega en orden FIFO = orden de seq.
     */
    @Override
    public boolean subirLote(List<Transaccion> lote) throws Exception {
        if (lote.isEmpty()) return true;
        if (lote.size() == 1) return subir(lote.get(0));
        long min = lote.get(0).seq();
        long max = lote.get(lote.size() - 1).seq();
        String nombre = URLEncoder.encode(
                "transactions/batch-" + min + "-" + max + ".json", StandardCharsets.UTF_8);
        String url = "https://storage.googleapis.com/upload/storage/v1/b/" + bucket
                + "/o?uploadType=media&name=" + nombre;
        StringBuilder sb = new StringBuilder(80 * lote.size());
        sb.append('[');
        for (int i = 0; i < lote.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Json.toJson(lote.get(i)));
        }
        sb.append(']');
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + obtenerToken())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString())).build();
        int sc = http.send(req, BodyHandlers.discarding()).statusCode();
        return sc >= 200 && sc < 300;
    }

    @Override
    public long contarExistentes() {
        long total = 0;
        String pageToken = null;
        try {
            do {
                String url = "https://storage.googleapis.com/storage/v1/b/" + bucket
                        + "/o?prefix=transactions/&fields=items/name,nextPageToken&maxResults=1000"
                        + (pageToken == null ? "" : "&pageToken=" + pageToken);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + obtenerToken())
                        .timeout(Duration.ofSeconds(10)).GET().build();
                JsonObject j = Json.parse(http.send(req, BodyHandlers.ofString()).body());
                if (j.has("items")) {
                    for (var item : j.getAsJsonArray("items")) {
                        total += txEnNombre(item.getAsJsonObject().get("name").getAsString());
                    }
                }
                pageToken = j.has("nextPageToken") ? j.get("nextPageToken").getAsString() : null;
            } while (pageToken != null);
        } catch (Exception e) {
            return 0; // si no podemos listar, empezamos el contador en 0
        }
        return total;
    }

    /** Cuantas tx representa un objeto: batch-{min}-{max}.json -> max-min+1; si no, 1. */
    private static long txEnNombre(String name) {
        int slash = name.lastIndexOf('/');
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        if (base.startsWith("batch-") && base.endsWith(".json")) {
            String medio = base.substring(6, base.length() - 5);
            int guion = medio.lastIndexOf('-');
            if (guion > 0) {
                try {
                    long min = Long.parseLong(medio.substring(0, guion));
                    long max = Long.parseLong(medio.substring(guion + 1));
                    if (max >= min) return max - min + 1;
                } catch (NumberFormatException ignored) { /* nombre raro: cuenta 1 */ }
            }
        }
        return 1;
    }

    @Override
    public List<Transaccion> leerTodas() throws Exception {
        List<Transaccion> txs = new ArrayList<>();
        String pageToken = null;
        do {
            String url = "https://storage.googleapis.com/storage/v1/b/" + bucket
                    + "/o?prefix=transactions/&fields=items/name,nextPageToken&maxResults=1000"
                    + (pageToken == null ? "" : "&pageToken=" + pageToken);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + obtenerToken())
                    .timeout(Duration.ofSeconds(10)).GET().build();
            JsonObject j = Json.parse(http.send(req, BodyHandlers.ofString()).body());
            if (j.has("items")) {
                for (var item : j.getAsJsonArray("items")) {
                    String nombre = item.getAsJsonObject().get("name").getAsString();
                    txs.addAll(descargarConReintentos(nombre));
                }
            }
            pageToken = j.has("nextPageToken") ? j.get("nextPageToken").getAsString() : null;
        } while (pageToken != null);
        return txs;
    }

    /**
     * Baja un objeto del log reintentando unas pocas veces ante fallos
     * transitorios (red, 5xx). Si agota los intentos relanza la ultima
     * excepcion: una descarga perdida corrompe el log si se reaplica a medias,
     * asi que el fallo debe propagarse y no tragarse aqui.
     */
    private List<Transaccion> descargarConReintentos(String nombre) throws Exception {
        Exception ultima = null;
        for (int i = 0; i < INTENTOS_DESCARGA; i++) {
            try {
                return descargar(nombre);
            } catch (Exception e) {
                ultima = e;
                try { Thread.sleep(100L * (i + 1)); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw new Exception("GCS: no se pudo descargar el objeto del log: " + nombre, ultima);
    }

    /** Baja un objeto del log; puede ser una tx o un lote (ver parsear). */
    private List<Transaccion> descargar(String nombre) throws Exception {
        String url = "https://storage.googleapis.com/storage/v1/b/" + bucket
                + "/o/" + URLEncoder.encode(nombre, StandardCharsets.UTF_8) + "?alt=media";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + obtenerToken())
                .timeout(Duration.ofSeconds(30)).GET().build();
        var resp = http.send(req, BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new Exception("GCS: HTTP " + resp.statusCode() + " al descargar " + nombre);
        }
        return parsear(resp.body());
    }

    /**
     * Un objeto del log puede ser una tx (un objeto JSON) o un lote (un array de
     * objetos). Se parsea a mano (campos simples: ints y longs). El orden no
     * importa aqui: la recuperacion reordena todo por seq antes de reaplicar.
     */
    private static List<Transaccion> parsear(String cuerpo) {
        JsonElement raiz = JsonParser.parseString(cuerpo);
        List<Transaccion> out = new ArrayList<>();
        if (raiz.isJsonArray()) {
            for (JsonElement e : raiz.getAsJsonArray()) out.add(deObjeto(e.getAsJsonObject()));
        } else {
            out.add(deObjeto(raiz.getAsJsonObject()));
        }
        return out;
    }

    private static Transaccion deObjeto(JsonObject j) {
        return new Transaccion(
                j.get("seq").getAsLong(),
                j.get("origenId").getAsInt(),
                j.get("destinoId").getAsInt(),
                j.get("montoCentavos").getAsLong());
    }

    private String obtenerToken() throws Exception {
        long ahora = System.currentTimeMillis();
        if (token != null && ahora < tokenVenceMs) return token;
        HttpRequest req = HttpRequest.newBuilder(URI.create(META_TOKEN))
                .header("Metadata-Flavor", "Google")
                .timeout(Duration.ofSeconds(5)).GET().build();
        JsonObject j = Json.parse(http.send(req, BodyHandlers.ofString()).body());
        token = j.get("access_token").getAsString();
        long venceEnS = j.has("expires_in") ? j.get("expires_in").getAsLong() : 3600;
        tokenVenceMs = ahora + (venceEnS - 60) * 1000L; // renovamos 60s antes
        return token;
    }
}
