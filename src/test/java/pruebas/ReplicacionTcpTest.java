package pruebas;

import com.escom.banco.data.CuentaRepository;
import com.escom.banco.model.Cuenta;
import com.escom.banco.replicacion.ClienteReplicacion;
import com.escom.banco.replicacion.ServidorReplicacion;

import java.util.Random;
import java.util.function.BooleanSupplier;

public final class ReplicacionTcpTest {

    private ReplicacionTcpTest() {}

    public static void run() throws Exception {
        CuentaRepository lider = repo(10, 100000);
        ServidorReplicacion servidor = new ServidorReplicacion(lider, 0); // puerto efimero
        servidor.iniciar();

        CuentaRepository replica = repo(10, 100000);
        ClienteReplicacion cliente = new ClienteReplicacion(replica, "127.0.0.1", servidor.getPuerto());
        cliente.iniciar();

        Random r = new Random(5);
        transferir(lider, r, 100);

        boolean alcanzo = esperar(
                () -> replica.secuenciaActual() == lider.registro().ultimaSecuencia(), 3000);
        MiniTest.check(alcanzo, "la replica alcanza al lider por TCP (catch-up + live)");
        MiniTest.check(mismosSaldos(lider, replica, 10), "saldos iguales tras streaming TCP");

        // mas transferencias: el streaming en vivo las debe propagar
        transferir(lider, r, 50);
        boolean alcanzo2 = esperar(
                () -> replica.secuenciaActual() == lider.registro().ultimaSecuencia(), 3000);
        MiniTest.check(alcanzo2, "el streaming en vivo propaga nuevas tx");
        MiniTest.check(mismosSaldos(lider, replica, 10), "saldos siguen iguales en vivo");

        cliente.detener();
        servidor.detener();
    }

    private static void transferir(CuentaRepository repo, Random r, int veces) {
        for (int k = 0; k < veces; k++) {
            repo.transferir(1 + r.nextInt(10), 1 + r.nextInt(10), 1 + r.nextInt(500));
        }
    }

    private static CuentaRepository repo(int n, long saldo) {
        CuentaRepository repo = new CuentaRepository();
        for (int i = 1; i <= n; i++) repo.put(new Cuenta(i, "n", "a", "b", saldo));
        return repo;
    }

    private static boolean esperar(BooleanSupplier cond, long msMax) throws InterruptedException {
        long fin = System.nanoTime() + msMax * 1_000_000L;
        while (System.nanoTime() < fin) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(5);
        }
        return cond.getAsBoolean();
    }

    private static boolean mismosSaldos(CuentaRepository a, CuentaRepository b, int n) {
        for (int i = 1; i <= n; i++) {
            if (a.get(i).getSaldoCentavos() != b.get(i).getSaldoCentavos()) return false;
        }
        return true;
    }
}
