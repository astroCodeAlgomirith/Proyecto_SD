package com.escom.banco.almacen;

import com.escom.banco.model.Transaccion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Log durable de transacciones en Cloud Storage. Escribe en segundo plano (una
 * cola + un hilo) para no bloquear la ruta de la transferencia: el lider sigue
 * siendo la fuente de verdad y el objeto en GCS va detras. El writer AGRUPA las
 * tx encoladas en un solo objeto por PUT (drainTo hasta MAX_LOTE): el costo de
 * subir a GCS lo domina el round-trip, no los bytes, asi que un lote de ~1000 tx
 * cuesta casi lo mismo que una sola y baja el costo por tx ~1000x; por eso el log
 * no se rezaga bajo carga, sin tocar el hot-path. La subida real se delega en
 * Subidor (asi se puede probar sin tocar la red).
 */
public final class AlmacenGcs {

    /** Subida de transacciones a GCS; devuelve true si quedaron persistidas. */
    public interface Subidor {
        boolean subir(Transaccion t) throws Exception;
        /**
         * Sube un lote como UN solo objeto, amortizando el round-trip HTTP sobre
         * muchas tx. Por defecto cae a una-por-una, para subidores simples (p. ej.
         * los de prueba) que no necesitan agrupar.
         */
        default boolean subirLote(List<Transaccion> lote) throws Exception {
            for (Transaccion t : lote) if (!subir(t)) return false;
            return true;
        }
        /** Cuantas tx ya existen en el log (para sembrar el contador). */
        default long contarExistentes() { return 0; }
        /** Todas las tx del log (para recuperar el estado en un arranque en frio). */
        default List<Transaccion> leerTodas() throws Exception { return List.of(); }
    }

    private static final int REINTENTOS = 3;
    // Tope de tx por objeto en GCS. La espera de poll() abajo actua de flush: una
    // tx sola se sube en <=200ms (no espera a llenar el lote); bajo carga, drainTo
    // junta hasta MAX_LOTE en un PUT.
    private static final int MAX_LOTE = 1000;

    private final Subidor subidor;
    private final BlockingQueue<Transaccion> cola = new LinkedBlockingQueue<>();
    private final AtomicLong escritos = new AtomicLong(0);
    private volatile boolean activo = false;
    private Thread worker;

    public AlmacenGcs(Subidor subidor) { this.subidor = subidor; }

    public void iniciar() {
        iniciar(subidor.contarExistentes());
    }

    /** Arranca el worker sembrando el contador con un valor ya conocido. */
    public void iniciar(long escritosIniciales) {
        escritos.set(Math.max(0, escritosIniciales));
        activo = true;
        worker = new Thread(this::correr, "gcs-log");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Reaplica todo el log durable sobre el repo (recuperacion en frio cuando
     * fallaron todos los nodos). Reaplica en orden de seq via aplicarReplica,
     * que no revalida saldos ni reescribe en GCS. Devuelve cuantas tx reaplico.
     */
    public long recuperar(Consumer<Transaccion> aplicar) {
        List<Transaccion> txs;
        try {
            txs = new ArrayList<>(subidor.leerTodas());
        } catch (Exception e) {
            // No degradamos a estado base en silencio: servir el CSV con
            // secuencia=0 haria que las nuevas tx reutilicen seqs ya usados y
            // corrompan el log durable. Fallamos ruidosamente para que el
            // arranque aborte y el nodo no sirva un estado falso.
            throw new IllegalStateException(
                    "GCS: no se pudo leer el log para recuperar; se aborta el "
                    + "arranque para no servir estado base corrupto", e);
        }
        txs.sort(Comparator.comparingLong(Transaccion::seq));
        for (Transaccion t : txs) aplicar.accept(t);
        return txs.size();
    }

    /** Encola una transaccion para persistirla (no bloquea). */
    public void registrar(Transaccion t) {
        if (activo) cola.offer(t);
    }

    public long escritos() { return escritos.get(); }
    public int pendientes() { return cola.size(); }

    public void detener() {
        activo = false;
        if (worker != null) worker.interrupt();
    }

    private void correr() {
        while (activo || !cola.isEmpty()) {
            Transaccion t;
            try {
                t = cola.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                if (!activo && cola.isEmpty()) break;
                continue;
            }
            if (t == null) continue;
            // Junta la primera tx con todo lo que ya este encolado (hasta MAX_LOTE)
            // y lo sube como un solo objeto. drainTo respeta el orden FIFO = seq.
            List<Transaccion> lote = new ArrayList<>();
            lote.add(t);
            cola.drainTo(lote, MAX_LOTE - 1);
            if (subirLoteConReintentos(lote)) {
                escritos.addAndGet(lote.size());
            } else {
                System.err.println("GCS: no se pudo persistir el lote seq="
                        + lote.get(0).seq() + ".." + lote.get(lote.size() - 1).seq());
            }
        }
    }

    private boolean subirLoteConReintentos(List<Transaccion> lote) {
        for (int i = 0; i < REINTENTOS; i++) {
            try {
                if (subidor.subirLote(lote)) return true;
            } catch (Exception e) {
                // reintentamos con un respiro
            }
            try { Thread.sleep(100L * (i + 1)); }
            catch (InterruptedException e) { return false; }
        }
        return false;
    }
}
