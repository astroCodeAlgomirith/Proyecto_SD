package pruebas;

import com.escom.banco.json.Json;
import com.escom.banco.server.BancoHttp;
import com.escom.banco.server.PaginaHandler;
import com.escom.banco.server.PanelHandler;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Verifica que /panel agrega el stats local + el de un peer vivo + marca como
 * caido un peer inalcanzable, y que el recurso dashboard.html viaja en el jar.
 */
public final class PanelTest {

    private PanelTest() {}

    public static void run() throws Exception {
        // Peer vivo: un nodo banco real con /stats.
        HttpServer peer = BancoHttp.crear(0);
        peer.start();
        int puertoPeer = peer.getAddress().getPort();

        // Nodo con el panel, apuntando al peer vivo y a un puerto cerrado.
        HttpServer a = HttpServer.create(new InetSocketAddress(0), 0);
        ExecutorService exA = Executors.newFixedThreadPool(2);
        a.setExecutor(exA);
        a.createContext("/panel", new PanelHandler(
                "A", List.of("127.0.0.1:" + puertoPeer, "127.0.0.1:1")));
        a.start();

        HttpClient cli = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + a.getAddress().getPort() + "/panel")).GET().build();
        JsonObject resp = Json.parse(cli.send(req, BodyHandlers.ofString()).body());
        JsonArray nodos = resp.getAsJsonArray("nodos");

        MiniTest.eq(3, nodos.size(), "el panel agrega 3 nodos (local + 2 peers)");

        JsonObject local = nodos.get(0).getAsJsonObject();
        MiniTest.eq("A", local.get("host").getAsString(), "primer nodo es el local");
        MiniTest.check(local.get("alcanzable").getAsBoolean(), "el nodo local esta alcanzable");
        MiniTest.check(local.has("rol") && local.has("saldoTotalCentavos"),
                "el nodo local trae sus stats");

        JsonObject vivo = nodos.get(1).getAsJsonObject();
        MiniTest.check(vivo.get("alcanzable").getAsBoolean(), "el peer vivo responde su /stats");

        JsonObject caido = nodos.get(2).getAsJsonObject();
        MiniTest.check(!caido.get("alcanzable").getAsBoolean(), "el peer cerrado se marca caido");

        String html = PaginaHandler.htmlRecurso();
        MiniTest.check(html != null && html.contains("/panel"),
                "dashboard.html viaja en el jar y consume /panel");
        MiniTest.check(html != null && html.contains("setInterval"),
                "el panel es reactivo (setInterval)");

        peer.stop(0);
        ((ExecutorService) peer.getExecutor()).shutdownNow();
        a.stop(0);
        exA.shutdownNow();
    }
}
