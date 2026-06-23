package pruebas;

import com.escom.banco.auth.JWTUtil;
import com.escom.banco.data.CuentaRepository;
import com.escom.banco.model.Cuenta;
import com.escom.banco.server.AccountHandler;
import com.escom.banco.server.TransferHandler;
import com.escom.banco.server.http.Respuesta;
import com.escom.banco.server.http.Solicitud;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Pruebas in-process de los handlers (sin socket): construye Solicitud a mano y
 * llama al manejador. Cubre:
 *  (b) codigos de error del body de /transfer -> 400/404 correctos;
 *  (c) header Authorization tolerante ("bearer x","BEARER x","Bearer  x");
 *  (d) GET de id fuera del rango int -> 404.
 */
public final class HandlersErroresTest {

    private static final TransferHandler TRANSFER = new TransferHandler();
    private static final AccountHandler ACCOUNT = new AccountHandler();
    private static String tok;

    private HandlersErroresTest() {}

    public static void run() {
        // Las cuentas 1..10 ya las puso ParserHttpTest en el singleton; aseguramos.
        CuentaRepository repo = CuentaRepository.get();
        for (int i = 1; i <= 10; i++) {
            if (repo.get(i) == null) repo.put(new Cuenta(i, "n" + i, "a", "b", 1_000_000));
        }
        tok = JWTUtil.generateToken("htest");

        erroresTransfer();
        authTolerante();
        idFueraDeRangoInt();
    }

    // ---- (b) codigos de error del body de /transfer ----
    private static void erroresTransfer() {
        MiniTest.eq(400, transfer("no es json {{{"), "JSON malo -> 400");
        MiniTest.eq(400, transfer("{\"sourceAccountId\":\"1\"}"), "campos faltantes -> 400");
        MiniTest.eq(400, transfer(body("1", "2", "abc")), "amount no numerico -> 400");
        MiniTest.eq(400, transfer(body("3", "3", "1.00")), "misma cuenta -> 400");
        MiniTest.eq(400, transfer(body("1", "2", "0")), "monto cero -> 400");
        MiniTest.eq(400, transfer(body("1", "2", "-1.00")), "monto negativo -> 400");
        MiniTest.eq(400, transfer(body("1", "2", "999999999.00")), "saldo insuficiente -> 400");
        MiniTest.eq(404, transfer(body("1", "9999", "1.00")), "cuenta inexistente -> 404");
        // Sanidad: una transferencia valida sigue dando 200.
        MiniTest.eq(200, transfer(body("1", "2", "1.00")), "transferencia valida -> 200");
    }

    // ---- (c) Authorization tolerante ----
    private static void authTolerante() {
        MiniTest.eq(200, getCuentaConAuth("bearer " + tok), "scheme 'bearer' minuscula -> 200");
        MiniTest.eq(200, getCuentaConAuth("BEARER " + tok), "scheme 'BEARER' mayuscula -> 200");
        MiniTest.eq(200, getCuentaConAuth("Bearer  " + tok), "doble espacio 'Bearer  x' -> 200");
        MiniTest.eq(401, getCuentaConAuth(tok), "sin scheme (solo token) -> 401");
        MiniTest.eq(401, getCuentaConAuth("Bearer " + tok + "roto"), "token invalido -> 401");
    }

    // ---- (d) GET id fuera del rango int ----
    private static void idFueraDeRangoInt() {
        // Numerico pero > Integer.MAX_VALUE: recurso inexistente -> 404, no 400.
        MiniTest.eq(404, getCuenta("/api/accounts/9999999999"), "id > INT_MAX -> 404");
        MiniTest.eq(404, getCuenta("/api/accounts/0"), "id 0 (< 1) -> 404");
        MiniTest.eq(400, getCuenta("/api/accounts/abc"), "id no numerico -> 400");
        MiniTest.eq(404, getCuenta("/api/accounts/12345"), "id numerico inexistente -> 404");
    }

    // ---- helpers ----

    private static String body(String src, String dst, String amount) {
        return "{\"sourceAccountId\":\"" + src + "\",\"targetAccountId\":\"" + dst
                + "\",\"amount\":\"" + amount + "\"}";
    }

    private static int transfer(String cuerpo) {
        Map<String, String> h = new HashMap<>();
        h.put("authorization", "Bearer " + tok);
        Solicitud s = new Solicitud("POST", "/api/transactions/transfer", h,
                cuerpo.getBytes(StandardCharsets.UTF_8), false);
        return TRANSFER.manejar(s).codigo;
    }

    private static int getCuenta(String path) {
        Map<String, String> h = new HashMap<>();
        h.put("authorization", "Bearer " + tok);
        Solicitud s = new Solicitud("GET", path, h, new byte[0], false);
        Respuesta r = ACCOUNT.manejar(s);
        return r.codigo;
    }

    private static int getCuentaConAuth(String authHeader) {
        Map<String, String> h = new HashMap<>();
        h.put("authorization", authHeader);
        Solicitud s = new Solicitud("GET", "/api/accounts/1", h, new byte[0], false);
        return ACCOUNT.manejar(s).codigo;
    }
}
