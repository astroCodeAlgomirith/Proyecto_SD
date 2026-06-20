package com.escom.banco.almacen;

import com.escom.banco.model.Transaccion;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    }

    private static final int REINTENTOS = 3;

    private final Subidor subidor;
    private final BlockingQueue<Transaccion> cola = new LinkedBlockingQueue<>();
    private final AtomicLong escritos = new AtomicLong(0);
    private volatile boolean activo = false;
    private Thread worker;

    public AlmacenGcs(Subidor subidor) { this.subidor = subidor; }

    public void iniciar() {
        escritos.set(Math.max(0, subidor.contarExistentes()));
        activo = true;
        worker = new Thread(this::correr, "gcs-log");
        worker.setDaemon(true);
        worker.start();
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
