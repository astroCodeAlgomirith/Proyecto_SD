package pruebas;

import com.escom.banco.data.CuentaRepository;
import com.escom.banco.model.Cuenta;
import com.escom.banco.model.Transaccion;

import java.util.Random;

public final class ReplicacionTest {

    private ReplicacionTest() {}

    public static void run() {
        logRegistraEnOrden();
        replicaAlcanzaAlLider();
        recuperacionDesdeSecuencia();
    }

    private static CuentaRepository repoConCuentas(int n, long saldoCentavos) {
        CuentaRepository repo = new CuentaRepository();
        for (int i = 1; i <= n; i++) repo.put(new Cuenta(i, "n", "a", "b", saldoCentavos));
        return repo;
    }

    private static void logRegistraEnOrden() {
        CuentaRepository lider = repoConCuentas(5, 100000);
        lider.transferir(1, 2, 100);
        lider.transferir(3, 4, 200);
        lider.transferir(2, 5, 50);
        MiniTest.eq(3L, (long) lider.registro().tamano(), "el log tiene 3 tx");
        MiniTest.eq(3L, lider.registro().ultimaSecuencia(), "ultima secuencia = 3");
        MiniTest.eq(1L, lider.registro().desde(0).get(0).seq(), "primera seq = 1");
        MiniTest.eq(3L, lider.registro().desde(0).get(2).seq(), "tercera seq = 3");
        MiniTest.eq(2L, (long) lider.registro().desde(1).size(), "desde(1) devuelve 2");
    }

    private static void replicaAlcanzaAlLider() {
        CuentaRepository lider = repoConCuentas(10, 100000);
        Random r = new Random(7);
        for (int k = 0; k < 200; k++) {
            lider.transferir(1 + r.nextInt(10), 1 + r.nextInt(10), 1 + r.nextInt(500));
        }
        CuentaRepository replica = repoConCuentas(10, 100000);
        for (Transaccion t : lider.registro().desde(0)) replica.aplicarReplica(t);

        MiniTest.check(mismosSaldos(lider, replica, 10), "la replica converge al lider");
        MiniTest.eq(lider.registro().ultimaSecuencia(), replica.secuenciaActual(),
                "la replica iguala la secuencia del lider");
        MiniTest.eq(lider.saldoTotalCentavos(), replica.saldoTotalCentavos(), "suma total igual");
    }

    private static void recuperacionDesdeSecuencia() {
        CuentaRepository lider = repoConCuentas(10, 100000);
        Random r = new Random(99);
        for (int k = 0; k < 300; k++) {
            lider.transferir(1 + r.nextInt(10), 1 + r.nextInt(10), 1 + r.nextInt(500));
        }
        // La replica solo aplico hasta la secuencia 150 y luego "revive".
        CuentaRepository replica = repoConCuentas(10, 100000);
        for (Transaccion t : lider.registro().desde(0)) {
            if (t.seq() > 150) break;
            replica.aplicarReplica(t);
        }
        MiniTest.eq(150L, replica.secuenciaActual(), "la replica se quedo en 150");

        // Catch-up incremental: pide solo de la 151 en adelante.
        for (Transaccion t : lider.registro().desde(replica.secuenciaActual())) {
            replica.aplicarReplica(t);
        }
        MiniTest.check(mismosSaldos(lider, replica, 10), "replica consistente tras el catch-up");
        MiniTest.eq(lider.registro().ultimaSecuencia(), replica.secuenciaActual(),
                "secuencia final igual a la del lider");
    }

    private static boolean mismosSaldos(CuentaRepository a, CuentaRepository b, int n) {
        for (int i = 1; i <= n; i++) {
            if (a.get(i).getSaldoCentavos() != b.get(i).getSaldoCentavos()) return false;
        }
        return true;
    }
}
