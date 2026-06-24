package pruebas;

import com.escom.banco.data.CuentaRepository;
import com.escom.banco.model.Cuenta;
import com.escom.banco.replicacion.ClienteReplicacion;
import com.escom.banco.replicacion.PuntoControl;
import com.escom.banco.replicacion.ServidorReplicacion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.function.BooleanSupplier;

/** Punto de control local de la replica: round-trip, robustez y resume E2E. */
public final class PuntoControlTest {

    private PuntoControlTest() {}

    public static void run() throws Exception {
        roundTrip();
        sinArchivo();
        archivoCorrupto();
        resumeE2E();
    }

    // 1. guardar -> cargar restaura saldos, secuencia y total de transferencias.
    private static void roundTrip() throws Exception {
        Path f = tmp("pc-rt");
        CuentaRepository a = repo(5, 100000);
        Random r = new Random(7);
        for (int k = 0; k < 40; k++) {
            a.transferir(1 + r.nextInt(5), 1 + r.nextInt(5), 1 + r.nextInt(200));
        }
        long seq = a.secuenciaActual();
        PuntoControl pc = new PuntoControl(f, 60);
        pc.guardarFinal(a);

        // "Reinicio": repo nuevo en base CSV; cargar el checkpoint.
        CuentaRepository b = repo(5, 100000);
        long cargada = pc.cargar(b);
        MiniTest.eq(seq, cargada, "cargar devuelve la secuencia guardada");
        MiniTest.eq(seq, b.secuenciaActual(), "secuencia restaurada");
        MiniTest.eq(seq, b.totalTransferencias(), "total de transferencias restaurado (= seq)");
        MiniTest.check(mismosSaldos(a, b, 5), "saldos restaurados desde el checkpoint");
        Files.deleteIfExists(f);
    }

    // 2. cargar sin archivo -> -1 y no toca el repo (arranca desde CSV).
    private static void sinArchivo() {
        CuentaRepository b = repo(3, 50000);
        PuntoControl pc = new PuntoControl(Path.of("no-existe-" + System.nanoTime() + ".bin"), 60);
        long cargada = pc.cargar(b);
        MiniTest.eq(-1, cargada, "cargar sin archivo devuelve -1");
        MiniTest.eq(0, b.secuenciaActual(), "repo intacto sin checkpoint");
    }

    // 3. archivo corrupto -> -1 (un checkpoint malo NUNCA impide arrancar).
    private static void archivoCorrupto() throws Exception {
        Path f = tmp("pc-bad");
        Files.write(f, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        CuentaRepository b = repo(3, 50000);
        PuntoControl pc = new PuntoControl(f, 60);
        long cargada = pc.cargar(b);
        MiniTest.eq(-1, cargada, "checkpoint corrupto devuelve -1 (fallback a CSV)");
        Files.deleteIfExists(f);
    }

    // 4. E2E: la replica reinicia, manda RESUME X>0 y el lider solo envia X+1..,
    //    SIN doble-aplicacion (saldos == lider Y total de transferencias == seq).
    private static void resumeE2E() throws Exception {
        Path f = tmp("pc-e2e");
        CuentaRepository lider = repo(8, 100000);
        ServidorReplicacion servidor = new ServidorReplicacion(lider, 0); // puerto efimero
        servidor.iniciar();

        CuentaRepository replica = repo(8, 100000);
        PuntoControl pc = new PuntoControl(f, 60);
        ClienteReplicacion cliente = new ClienteReplicacion(replica, "127.0.0.1", servidor.getPuerto());
        cliente.setPuntoControl(pc);
        cliente.iniciar();

        Random r = new Random(11);
        transferir(lider, r, 80);
        boolean alcanzo = esperar(
                () -> replica.secuenciaActual() == lider.registro().ultimaSecuencia(), 3000);
        MiniTest.check(alcanzo, "la replica alcanza al lider (E2E)");

        long seqCorte = replica.secuenciaActual();
        MiniTest.check(seqCorte > 0, "hay una secuencia > 0 que persistir");
        pc.guardarFinal(replica); // snapshot en el punto de corte
        cliente.detener();

        // El lider sigue recibiendo transferencias mientras la replica esta "muerta".
        transferir(lider, r, 50);

        // "Reinicio" de la replica: repo nuevo en base CSV + cargar el checkpoint.
        CuentaRepository replica2 = repo(8, 100000);
        long cargada = pc.cargar(replica2);
        MiniTest.eq(seqCorte, cargada, "la replica reinicia desde su secuencia exacta (>0)");

        ClienteReplicacion cliente2 = new ClienteReplicacion(replica2, "127.0.0.1", servidor.getPuerto());
        cliente2.setPuntoControl(pc);
        cliente2.iniciar();

        boolean alcanzo2 = esperar(
                () -> replica2.secuenciaActual() == lider.registro().ultimaSecuencia(), 3000);
        MiniTest.check(alcanzo2, "la replica reanudada alcanza al lider");
        MiniTest.check(mismosSaldos(lider, replica2, 8),
                "saldos == lider tras reanudar (sin doble-aplicacion)");
        MiniTest.eq(lider.secuenciaActual(), replica2.totalTransferencias(),
                "total de transferencias == seq final (sin doble conteo)");

        cliente2.detener();
        servidor.detener();
        Files.deleteIfExists(f);
    }

    private static Path tmp(String prefijo) throws Exception {
        Path f = Files.createTempFile(prefijo, ".bin");
        Files.delete(f); // que el checkpoint se cree limpio
        return f;
    }

    private static void transferir(CuentaRepository repo, Random r, int veces) {
        for (int k = 0; k < veces; k++) {
            repo.transferir(1 + r.nextInt(8), 1 + r.nextInt(8), 1 + r.nextInt(300));
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
