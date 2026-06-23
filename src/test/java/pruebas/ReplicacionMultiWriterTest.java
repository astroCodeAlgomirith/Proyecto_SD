package pruebas;

import com.escom.banco.data.CuentaRepository;
import com.escom.banco.model.Cuenta;
import com.escom.banco.model.Transaccion;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Multi-writer de replicacion: 8 hilos escriben miles de transferencias sobre
 * PARES DISJUNTOS en un repo "lider". Un lector drena el log via
 * registro().desde(enviado) (como ServidorReplicacion) y reaplica en una
 * "replica" con aplicarReplica. Al final el saldo POR CUENTA del lider debe
 * coincidir con el de la replica para TODAS las cuentas (no solo la suma global).
 *
 * Sin el seqLock, seq se asigna con un atomic global pero el agregar() al log
 * ocurre solo bajo los locks del par; el lector (tailMap, seq estrictamente
 * mayor) puede saltarse una seq aun no insertada y perderla -> la replica
 * diverge. Con el seqLock este test debe pasar.
 */
public final class ReplicacionMultiWriterTest {

    private ReplicacionMultiWriterTest() {}

    public static void run() throws InterruptedException {
        final int n = 64;          // 32 pares disjuntos
        final long saldoIni = 1_000_000;

        CuentaRepository lider = new CuentaRepository();
        CuentaRepository replica = new CuentaRepository();
        for (int i = 1; i <= n; i++) {
            lider.put(new Cuenta(i, "n" + i, "a", "b", saldoIni));
            replica.put(new Cuenta(i, "n" + i, "a", "b", saldoIni));
        }

        AtomicBoolean correr = new AtomicBoolean(true);
        final long[] enviado = {0};
        Thread lector = new Thread(() -> {
            while (correr.get()) {
                List<Transaccion> nuevas = lider.registro().desde(enviado[0]);
                for (Transaccion tx : nuevas) {
                    replica.aplicarReplica(tx);
                    enviado[0] = tx.seq();
                }
            }
            for (Transaccion tx : lider.registro().desde(enviado[0])) {
                replica.aplicarReplica(tx);
                enviado[0] = tx.seq();
            }
        });
        lector.start();

        int hilos = 8, ops = 3000;
        Thread[] ts = new Thread[hilos];
        for (int h = 0; h < hilos; h++) {
            // Cada hilo opera sobre un par disjunto: cuentas (2h+1, 2h+2),
            // (2h+17, 2h+18), ... asi ningun par solapa con el de otro hilo.
            final int base = 2 * h + 1;
            final long seed = h * 31L + 7;
            ts[h] = new Thread(() -> {
                java.util.Random r = new java.util.Random(seed);
                int parejas = (n / 2) / hilos;  // bloques de pares para este hilo
                for (int k = 0; k < ops; k++) {
                    int bloque = r.nextInt(parejas);
                    int a = base + bloque * (2 * hilos);
                    int b = a + 1;
                    // Alterna direccion para crear deltas por cuenta no triviales.
                    if (r.nextBoolean()) lider.transferir(a, b, 1 + r.nextInt(500));
                    else                 lider.transferir(b, a, 1 + r.nextInt(500));
                }
            });
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
        correr.set(false);
        lector.join();

        // El log se debe haber drenado por completo.
        MiniTest.eq(lider.registro().tamano(), (int) replica.totalTransferencias(),
                "replica aplico TODAS las tx del log del lider (sin saltos de seq)");

        // Invariante global de suma en ambos repos.
        MiniTest.eq((long) n * saldoIni, lider.saldoTotalCentavos(),
                "suma global conservada en el lider");
        MiniTest.eq((long) n * saldoIni, replica.saldoTotalCentavos(),
                "suma global conservada en la replica");

        // Igualdad POR CUENTA lider == replica para TODAS las cuentas.
        int distintas = 0;
        for (int i = 1; i <= n; i++) {
            if (lider.get(i).getSaldoCentavos() != replica.get(i).getSaldoCentavos()) distintas++;
        }
        MiniTest.eq(0L, distintas,
                "saldo por cuenta lider == replica en TODAS las cuentas (sin divergencia)");
    }
}
