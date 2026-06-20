package pruebas;

import com.escom.banco.carga.GeneradorCarga;
import com.escom.banco.data.CuentaRepository;
import com.escom.banco.model.Cuenta;
import com.escom.banco.server.BancoHttp;
import com.escom.banco.server.nio.ServidorNio;

/**
 * Corre el generador de carga ~2s contra un nodo HTTP real en proceso (puerto
 * efimero) y verifica que el invariante de conservacion se mantiene y que hubo
 * lecturas y transferencias reales sobre la pila HTTP.
 */
public final class GeneradorCargaTest {

    private GeneradorCargaTest() {}

    public static void run() throws Exception {
        int n = 20;
        long saldo = 1_000_000; // centavos por cuenta
        CuentaRepository repo = CuentaRepository.get();
        for (int i = 1; i <= n; i++) repo.put(new Cuenta(i, "n" + i, "a", "b", saldo));
        long totalAntes = repo.saldoTotalCentavos();

        ServidorNio server = BancoHttp.crear(0);
        server.iniciar();
        int puerto = server.puerto();

        GeneradorCarga.Config cfg = new GeneradorCarga.Config("127.0.0.1", puerto, 2, 16, 500);
        GeneradorCarga.Resultado r = new GeneradorCarga(cfg).ejecutar();

        server.detener();

        MiniTest.check(r.lecturas > 0, "el generador realizo lecturas via HTTP");
        MiniTest.check(r.transOk > 0, "el generador realizo transferencias exitosas via HTTP");
        MiniTest.eq(totalAntes, repo.saldoTotalCentavos(), "saldo total se conserva tras la carga");
        MiniTest.check(r.invarianteOk(), "el generador reporta invariante OK");
        MiniTest.eq(n, r.cuentas, "el generador descubrio el numero de cuentas via /stats");
    }
}
