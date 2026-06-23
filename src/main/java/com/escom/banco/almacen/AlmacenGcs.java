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
 * siendo la fuente de verdad y el objeto en GCS va detras. La subida real se
 * delega en Subidor (asi se puede probar sin tocar la red).
 */
public final class AlmacenGcs {

    /** Subida de una transaccion a GCS; devuelve true si quedo persistida. */
    public interface Subidor {
        boolean subir(Transaccion t) throws Exception;
        /** Cuantos objetos ya existen en el bucket (para sembrar el contador). */
        default long contarExistentes() { return 0; }
        /** Todas las tx del log (para recuperar el estado en un arranque en frio). */
        default List<Transaccion> leerTodas() throws Exception { return List.of(); }
    }

    private static final int REINTENTOS = 3;

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
            if (subirConReintentos(t)) {
                escritos.incrementAndGet();
            } else {
                System.err.println("GCS: no se pudo persistir la tx seq=" + t.seq());
            }
        }
    }

    private boolean subirConReintentos(Transaccion t) {
        for (int i = 0; i < REINTENTOS; i++) {
            try {
                if (subidor.subir(t)) return true;
            } catch (Exception e) {
                // reintentamos con un respiro
            }
            try { Thread.sleep(100L * (i + 1)); }
            catch (InterruptedException e) { return false; }
        }
        return false;
    }
}
