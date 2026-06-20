package pruebas;

import com.escom.banco.json.Json;
import com.escom.banco.server.BancoHttp;
import com.escom.banco.server.PaginaHandler;
import com.escom.banco.server.PanelHandler;
import com.escom.banco.server.http.Ruteador;
import com.escom.banco.server.nio.ServidorNio;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;

/**
 * Verifica que /panel agrega el stats local + el de un peer vivo + marca como
 * caido un peer inalcanzable, y que el recurso dashboard.html viaja en el jar.
 */
public final class PanelTest {

    private PanelTest() {}

    public static void run() throws Exception {
        // Peer vivo: un nodo banco real con /stats.
        ServidorNio peer = BancoHttp.crear(0);
        peer.iniciar();
        int puertoPeer = peer.puerto();

        // Nodo con SOLO el panel (marcado WORKER: hace HttpClient.send bloqueante),
        // apuntando al peer vivo y a un puerto cerrado.
        Ruteador r = new Ruteador().registrar("/panel",
                new PanelHandler("A", List.of("127.0.0.1:" + puertoPeer, "127.0.0.1:1")),
                Ruteador.WORKER);
        ServidorNio a = new ServidorNio(0, r, 1, 2);
        a.iniciar();

        HttpClient cli = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + a.puerto() + "/panel")).GET().build();
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

        peer.detener();
        a.detener();
    }
}
