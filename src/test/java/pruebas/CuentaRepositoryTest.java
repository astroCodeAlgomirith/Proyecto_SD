package pruebas;

import com.escom.banco.data.CuentaRepository;
import com.escom.banco.data.CuentaRepository.Estado;
import com.escom.banco.model.Cuenta;

public final class CuentaRepositoryTest {

    private CuentaRepositoryTest() {}

    public static void run() throws InterruptedException {
        transferenciaBasica();
        casosBorde();
        invarianteBajoConcurrencia();
        snapshotConsistenteBajoCarga();
        detectaDescuadre();
    }

    // El total debe ser una FOTO consistente aun bajo carga: mientras 8 hilos
    // martillean transferencias, un lector muestrea saldoTotalCentavos() y cada
    // lectura tiene que dar el invariante exacto (sin "costuras" de un movimiento
    // a medias). Sin el snapshotLock esta prueba veria un total torcido y fallaria.
    private static void snapshotConsistenteBajoCarga() throws InterruptedException {
        CuentaRepository repo = new CuentaRepository();
        int n = 2000;
        long porCuenta = 100000;
        for (int i = 1; i <= n; i++) repo.put(new Cuenta(i, "n", "a", "b", porCuenta));
        final long esperado = (long) n * porCuenta;

        final java.util.concurrent.atomic.AtomicBoolean corriendo =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        final java.util.concurrent.atomic.AtomicLong torcido =
                new java.util.concurrent.atomic.AtomicLong(esperado);
        final java.util.concurrent.atomic.AtomicLong muestras =
                new java.util.concurrent.atomic.AtomicLong(0);

        int hilos = 8;
        Thread[] ws = new Thread[hilos];
        for (int h = 0; h < hilos; h++) {
            final long seed = h;
            ws[h] = new Thread(() -> {
                java.util.Random r = new java.util.Random(seed);
                while (corriendo.get()) {
                    repo.transferir(1 + r.nextInt(n), 1 + r.nextInt(n), 1 + r.nextInt(100));
                }
            });
        }
        Thread lector = new Thread(() -> {
            while (corriendo.get()) {
                long total = repo.saldoTotalCentavos();
                muestras.incrementAndGet();
                if (total != esperado) { torcido.set(total); corriendo.set(false); }
            }
        });

        for (Thread t : ws) t.start();
        lector.start();
        Thread.sleep(400);
        corriendo.set(false);
        for (Thread t : ws) t.join();
        lector.join();

        MiniTest.eq(esperado, torcido.get(),
                "saldoTotalCentavos es foto consistente bajo carga (" + muestras.get() + " muestras)");
    }

    // saldoTotalCentavos() debe RECALCULAR desde los saldos reales para poder
    // detectar un descuadre; un contador incremental se "conserva" solo y vuelve
    // tautologica la verificacion del invariante.
    private static void detectaDescuadre() {
        CuentaRepository repo = new CuentaRepository();
        repo.put(new Cuenta(1, "A", "A", "A", 50000));
        repo.put(new Cuenta(2, "B", "B", "B", 50000));
        MiniTest.eq(100000L, repo.saldoTotalCentavos(), "total inicial real");
        // Descuadre fuera de una transferencia que conserva: corrupcion, replica
        // mal aplicada o escritura parcial mueven un saldo sin su contraparte.
        repo.get(1).setSaldoCentavos(repo.get(1).getSaldoCentavos() - 10000);
        MiniTest.eq(90000L, repo.saldoTotalCentavos(),
                "saldoTotalCentavos detecta el descuadre (suma real, no contador cacheado)");
    }

    private static void transferenciaBasica() {
        CuentaRepository repo = new CuentaRepository();
        repo.put(new Cuenta(1, "A", "A", "A", 50000));   // 500.00
        repo.put(new Cuenta(2, "B", "B", "B", 50000));
        CuentaRepository.Resultado r = repo.transferir(1, 2, 10050); // 100.50
        MiniTest.eq(Estado.OK, r.estado, "estado OK");
        MiniTest.eq(1L, r.seq, "primera secuencia = 1");
        MiniTest.eq(39950L, repo.get(1).getSaldoCentavos(), "saldo origen");
        MiniTest.eq(60050L, repo.get(2).getSaldoCentavos(), "saldo destino");
        MiniTest.eq(100000L, repo.saldoTotalCentavos(), "suma constante");
        MiniTest.eq(1L, repo.totalTransferencias(), "contador de transferencias");
    }

    private static void casosBorde() {
        CuentaRepository repo = new CuentaRepository();
        repo.put(new Cuenta(1, "A", "A", "A", 100));
        repo.put(new Cuenta(2, "B", "B", "B", 100));
        MiniTest.eq(Estado.MISMA_CUENTA, repo.transferir(1, 1, 10).estado, "misma cuenta");
        MiniTest.eq(Estado.MONTO_INVALIDO, repo.transferir(1, 2, 0).estado, "monto cero");
        MiniTest.eq(Estado.MONTO_INVALIDO, repo.transferir(1, 2, -5).estado, "monto negativo");
        MiniTest.eq(Estado.NO_ENCONTRADA, repo.transferir(1, 99, 10).estado, "destino inexistente");
        MiniTest.eq(Estado.SALDO_INSUFICIENTE, repo.transferir(1, 2, 200).estado, "saldo insuficiente");
        MiniTest.eq(100L, repo.get(1).getSaldoCentavos(), "saldo intacto tras los fallos");
    }

    private static void invarianteBajoConcurrencia() throws InterruptedException {
        CuentaRepository repo = new CuentaRepository();
        int n = 50;
        for (int i = 1; i <= n; i++) repo.put(new Cuenta(i, "n", "a", "b", 100000));
        long totalAntes = repo.saldoTotalCentavos();

        int hilos = 8, opsPorHilo = 5000;
        Thread[] ts = new Thread[hilos];
        for (int h = 0; h < hilos; h++) {
            final long seed = h;
            ts[h] = new Thread(() -> {
                java.util.Random r = new java.util.Random(seed);
                for (int k = 0; k < opsPorHilo; k++) {
                    repo.transferir(1 + r.nextInt(n), 1 + r.nextInt(n), 1 + r.nextInt(100));
                }
            });
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
        MiniTest.eq(totalAntes, repo.saldoTotalCentavos(),
                "invariante de suma tras 40k transferencias concurrentes");
    }
}
