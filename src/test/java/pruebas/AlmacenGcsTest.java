package pruebas;

import com.escom.banco.almacen.AlmacenGcs;
import com.escom.banco.data.CuentaRepository;
import com.escom.banco.model.Cuenta;
import com.escom.banco.model.Transaccion;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Pruebas del log durable usando un Subidor falso (sin tocar GCS ni la red). */
public final class AlmacenGcsTest {

    private AlmacenGcsTest() {}

    public static void run() throws Exception {
        persisteEnOrden();
        reintentaTrasFallos();
        siembraConteoExistente();
        seEnganchaAlCommit();
    }

    /** Un subidor que recuerda lo subido y puede fallar las primeras N veces. */
    private static final class SubidorFalso implements AlmacenGcs.Subidor {
        final ConcurrentLinkedQueue<Long> subidos = new ConcurrentLinkedQueue<>();
        final AtomicInteger fallosRestantes = new AtomicInteger(0);
        final long existentes;
        SubidorFalso(int fallos, long existentes) {
            this.fallosRestantes.set(fallos);
            this.existentes = existentes;
        }
        @Override public boolean subir(Transaccion t) {
            if (fallosRestantes.getAndDecrement() > 0) return false;
            subidos.add(t.seq());
            return true;
        }
        @Override public long contarExistentes() { return existentes; }
    }

    private static void persisteEnOrden() throws Exception {
        SubidorFalso s = new SubidorFalso(0, 0);
        AlmacenGcs almacen = new AlmacenGcs(s);
        almacen.iniciar();
        for (long seq = 1; seq <= 5; seq++) almacen.registrar(new Transaccion(seq, 1, 2, 100));
        boolean ok = esperar(() -> almacen.escritos() == 5, 2000);
        MiniTest.check(ok, "persiste las 5 transacciones encoladas");
        MiniTest.eq(List.of(1L, 2L, 3L, 4L, 5L), List.copyOf(s.subidos), "las persiste en orden de seq");
        almacen.detener();
    }

    private static void reintentaTrasFallos() throws Exception {
        SubidorFalso s = new SubidorFalso(2, 0); // falla 2 veces, luego ok
        AlmacenGcs almacen = new AlmacenGcs(s);
        almacen.iniciar();
        almacen.registrar(new Transaccion(7, 1, 2, 50));
        boolean ok = esperar(() -> almacen.escritos() == 1, 3000);
        MiniTest.check(ok, "reintenta y termina persistiendo tras fallos transitorios");
        almacen.detener();
    }

    private static void siembraConteoExistente() {
        SubidorFalso s = new SubidorFalso(0, 42);
        AlmacenGcs almacen = new AlmacenGcs(s);
        almacen.iniciar();
        MiniTest.eq(42, almacen.escritos(), "siembra el contador con los objetos ya existentes");
        almacen.detener();
    }

    private static void seEnganchaAlCommit() throws Exception {
        CuentaRepository repo = new CuentaRepository();
        repo.put(new Cuenta(1, "a", "b", "c", 100000));
        repo.put(new Cuenta(2, "d", "e", "f", 100000));
        SubidorFalso s = new SubidorFalso(0, 0);
        AlmacenGcs almacen = new AlmacenGcs(s);
        almacen.iniciar();
        repo.setObservador(almacen::registrar);

        repo.transferir(1, 2, 250);
        boolean ok = esperar(() -> almacen.escritos() == 1, 2000);
        MiniTest.check(ok, "una transferencia confirmada se manda al log durable");
        almacen.detener();
    }

    private static boolean esperar(BooleanSupplier cond, long msMax) throws InterruptedException {
        long fin = System.nanoTime() + msMax * 1_000_000L;
        while (System.nanoTime() < fin) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(5);
        }
        return cond.getAsBoolean();
    }
}
