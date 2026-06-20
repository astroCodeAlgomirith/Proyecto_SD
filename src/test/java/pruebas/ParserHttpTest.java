package pruebas;

import com.escom.banco.data.CuentaRepository;
import com.escom.banco.json.Json;
import com.escom.banco.model.Cuenta;
import com.escom.banco.server.BancoHttp;
import com.escom.banco.server.nio.ServidorNio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;

/**
 * Prueba el parser/escritura HTTP/1.1 del servidor NIO con sockets crudos, en
 * los casos borde que el HttpClient de alto nivel no ejercita: body partido en
 * dos segmentos TCP, dos requests en un mismo segmento (pipelining/keep-alive),
 * 405 con Content-Length 0 que NO rompe la conexion, query string, header
 * Authorization case-insensitive y ausencia de Authorization.
 */
public final class ParserHttpTest {

    private ParserHttpTest() {}

    public static void run() throws Exception {
        CuentaRepository repo = CuentaRepository.get();
        for (int i = 1; i <= 10; i++) repo.put(new Cuenta(i, "n" + i, "a", "b", 1_000_000));

        ServidorNio server = BancoHttp.crear(0);
        server.iniciar();
        int puerto = server.puerto();
        String base = "http://127.0.0.1:" + puerto;
        String tok = obtenerToken(base);

        try {
            keepAliveDosRequests(puerto);
            pipelinedEnUnSegmento(puerto);
            metodoNoPermitidoNoRompeConexion(puerto, tok);
            queryStringSeIgnora(puerto, tok);
            sinAutorizacionDa401(puerto);
            bodyPartidoEnDosSegmentos(puerto, tok);
            headerCaseInsensitive(puerto, tok);
        } finally {
            server.detener();
        }
    }

    private static void keepAliveDosRequests(int puerto) throws IOException {
        try (Socket s = new Socket("127.0.0.1", puerto)) {
            OutputStream out = s.getOutputStream();
            enviar(out, "GET /stats HTTP/1.1\r\nHost: x\r\n\r\n");
            MiniTest.eq(200, leerResp(s.getInputStream())[0], "keep-alive: 1a request GET /stats -> 200");
            enviar(out, "GET /stats HTTP/1.1\r\nHost: x\r\n\r\n");
            MiniTest.eq(200, leerResp(s.getInputStream())[0], "keep-alive: 2a request en la MISMA conexion -> 200");
        }
    }

    private static void pipelinedEnUnSegmento(int puerto) throws IOException {
        try (Socket s = new Socket("127.0.0.1", puerto)) {
            enviar(s.getOutputStream(),
                    "GET /stats HTTP/1.1\r\nHost: x\r\n\r\nGET /stats HTTP/1.1\r\nHost: x\r\n\r\n");
            InputStream in = s.getInputStream();
            MiniTest.eq(200, leerResp(in)[0], "pipelining: 1a respuesta de dos requests en un segmento");
            MiniTest.eq(200, leerResp(in)[0], "pipelining: 2a respuesta (carry-over en el buffer)");
        }
    }

    private static void metodoNoPermitidoNoRompeConexion(int puerto, String tok) throws IOException {
        try (Socket s = new Socket("127.0.0.1", puerto)) {
            OutputStream out = s.getOutputStream();
            enviar(out, "PUT /api/accounts/1 HTTP/1.1\r\nHost: x\r\n\r\n");
            MiniTest.eq(405, leerResp(s.getInputStream())[0], "metodo no permitido -> 405 con Content-Length: 0");
            // La conexion debe seguir viva para la siguiente request.
            enviar(out, "GET /api/accounts/1 HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer " + tok + "\r\n\r\n");
            MiniTest.eq(200, leerResp(s.getInputStream())[0], "tras 405 la conexion keep-alive sigue viva -> 200");
        }
    }

    private static void queryStringSeIgnora(int puerto, String tok) throws IOException {
        try (Socket s = new Socket("127.0.0.1", puerto)) {
            enviar(s.getOutputStream(),
                    "GET /api/accounts/5?x=y HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer " + tok + "\r\n\r\n");
            MiniTest.eq(200, leerResp(s.getInputStream())[0], "query string '?x=y' se quita antes del parseInt -> 200");
        }
    }

    private static void sinAutorizacionDa401(int puerto) throws IOException {
        try (Socket s = new Socket("127.0.0.1", puerto)) {
            enviar(s.getOutputStream(), "GET /api/accounts/5 HTTP/1.1\r\nHost: x\r\n\r\n");
            MiniTest.eq(401, leerResp(s.getInputStream())[0], "sin header Authorization -> 401");
        }
    }

    private static void bodyPartidoEnDosSegmentos(int puerto, String tok) throws IOException {
        String body = "{\"sourceAccountId\":\"5\",\"targetAccountId\":\"6\",\"amount\":\"1.00\"}";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String cab = "POST /api/transactions/transfer HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer " + tok
                + "\r\nContent-Type: application/json\r\nContent-Length: " + b.length + "\r\n\r\n";
        try (Socket s = new Socket("127.0.0.1", puerto)) {
            OutputStream out = s.getOutputStream();
            int mitad = b.length / 2;
            out.write(cab.getBytes(StandardCharsets.ISO_8859_1));
            out.write(b, 0, mitad);
            out.flush();
            try { Thread.sleep(60); } catch (InterruptedException ignore) {}
            out.write(b, mitad, b.length - mitad);
            out.flush();
            MiniTest.eq(200, leerResp(s.getInputStream())[0], "body partido en dos segmentos TCP -> 200");
        }
    }

    private static void headerCaseInsensitive(int puerto, String tok) throws IOException {
        try (Socket s = new Socket("127.0.0.1", puerto)) {
            enviar(s.getOutputStream(),
                    "GET /api/accounts/5 HTTP/1.1\r\nhost: x\r\nauthorization: Bearer " + tok + "\r\n\r\n");
            MiniTest.eq(200, leerResp(s.getInputStream())[0], "header 'authorization' en minusculas -> 200");
        }
    }

    // ---- helpers ----

    private static void enviar(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /** Lee UNA respuesta completa (status + headers + Content-Length body). Devuelve [codigo, body]. */
    private static Object[] leerResp(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b, m = 0;
        while ((b = in.read()) != -1) {
            head.write(b);
            if (m == 0) m = (b == 13) ? 1 : 0;
            else if (m == 1) m = (b == 10) ? 2 : (b == 13 ? 1 : 0);
            else if (m == 2) m = (b == 13) ? 3 : 0;
            else m = (b == 10) ? 4 : (b == 13 ? 1 : 0);
            if (m == 4) break;
        }
        String headers = head.toString(StandardCharsets.ISO_8859_1);
        if (headers.isEmpty()) throw new IOException("respuesta vacia (conexion cerrada)");
        int codigo = Integer.parseInt(headers.split("\r\n", 2)[0].split(" ")[1]);
        int cl = 0;
        for (String linea : headers.split("\r\n")) {
            if (linea.toLowerCase().startsWith("content-length:")) {
                cl = Integer.parseInt(linea.substring(linea.indexOf(':') + 1).trim());
            }
        }
        byte[] body = new byte[cl];
        int off = 0;
        while (off < cl) {
            int n = in.read(body, off, cl - off);
            if (n < 0) break;
            off += n;
        }
        return new Object[]{codigo, new String(body, StandardCharsets.UTF_8)};
    }

    private static String obtenerToken(String base) throws Exception {
        HttpClient cli = HttpClient.newHttpClient();
        String cred = "{\"username\":\"ptest\",\"password\":\"ptest\"}";
        cli.send(post(base + "/api/register", cred), BodyHandlers.discarding());
        String r = cli.send(post(base + "/api/login", cred), BodyHandlers.ofString()).body();
        return Json.parse(r).get("token").getAsString();
    }

    private static HttpRequest post(String url, String body) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
