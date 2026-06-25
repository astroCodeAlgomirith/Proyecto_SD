package com.escom.banco.data;

import com.escom.banco.model.Cuenta;
import com.escom.banco.model.Transaccion;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Almacen en memoria de las cuentas, sin motor de base de datos.
 * Las transferencias toman el lock de las dos cuentas en orden de id para
 * evitar deadlock, y la suma total se conserva exacta (centavos long).
 */
public class CuentaRepository {

    private static final CuentaRepository INSTANCIA = new CuentaRepository();
    public static CuentaRepository get() { return INSTANCIA; }

    private final ConcurrentHashMap<Integer, Cuenta> cuentas = new ConcurrentHashMap<>();

    // Num. de secuencia de transaccion (base de la replicacion y el log GCS).
    private final AtomicLong secuencia = new AtomicLong(0);
    // LongAdder (no AtomicLong): es solo contador para /stats; en el hot path
    // hace add sin CAS global, sum() solo lo lee /stats. Quita 1 de los 2 puntos
    // de serializacion global por transferencia (secuencia es el otro, ineludible).
    private final LongAdder totalTransferencias = new LongAdder();
    private volatile long ultimaTxId = 0;
    private final RegistroTransacciones registro = new RegistroTransacciones();

    // Serializa el tramo secuencia+log+observador para que el orden de
    // insercion en el registro == orden de seq. Es el lock mas interno
    // (se toma despues de los locks de par y se libera antes) -> sin deadlock.
    private final java.util.concurrent.locks.ReentrantLock seqLock =
            new java.util.concurrent.locks.ReentrantLock();

    // Snapshot global: cada movimiento (transferir / aplicarReplica) toma el lock
    // COMPARTIDO mientras hace debito+credito; saldoTotalCentavos() toma el lock
    // EXCLUSIVO mientras recorre las cuentas. Asi el agregado nunca observa un
    // estado a medias (debitado sin acreditar) y los 3 nodos cuadran al centavo
    // aun bajo carga. Sigue siendo suma real (detecta descuadres), no un contador.
    // Los movimientos corren concurrentes entre si (lock compartido); solo el
    // escaneo, raro (cada ~2s en /stats), los pausa unos ms para tomar la foto.
    private final StampedLock snapshotLock = new StampedLock();

    // Observador del log durable (lo pone el lider): se le avisa cada commit.
    private volatile Consumer<Transaccion> observador = t -> {};
    // Conteo de tx en el log durable (Cloud Storage); -1 = deshabilitado.
    private volatile LongSupplier conteoStorage = () -> -1L;

    public void setObservador(Consumer<Transaccion> o) { this.observador = o; }
    public void setConteoStorage(LongSupplier s) { this.conteoStorage = s; }
    public long txEnStorage() { return conteoStorage.getAsLong(); }

    public void put(Cuenta c) { cuentas.put(c.id, c); }
    public Cuenta get(int id) { return cuentas.get(id); }
    public int tamano() { return cuentas.size(); }
    /** Vista de todas las cuentas (para el snapshot del punto de control). */
    public Collection<Cuenta> cuentas() { return cuentas.values(); }

    public long totalTransferencias() { return totalTransferencias.sum(); }
    public long ultimaTxId() { return ultimaTxId; }
    public long secuenciaActual() { return secuencia.get(); }
    public RegistroTransacciones registro() { return registro; }

    /**
     * Suma de todos los saldos en centavos (para verificar el invariante). Toma
     * el lock EXCLUSIVO del snapshot para que ningun movimiento este a medias
     * (debitado sin acreditar) durante el recorrido: el total es una foto
     * consistente, no una suma con costuras. Sigue recalculando desde los saldos
     * reales, asi que detecta un descuadre (no es un contador que se conserva solo).
     */
    public long saldoTotalCentavos() {
        long stamp = snapshotLock.writeLock();
        try {
            long total = 0;
            for (Cuenta c : cuentas.values()) total += c.getSaldoCentavos();
            return total;
        } finally {
            snapshotLock.unlockWrite(stamp);
        }
    }

    public enum Estado { OK, MISMA_CUENTA, MONTO_INVALIDO, NO_ENCONTRADA, SALDO_INSUFICIENTE }

    public static final class Resultado {
        public final Estado estado;
        public final long seq;
        private Resultado(Estado estado, long seq) { this.estado = estado; this.seq = seq; }
        static Resultado de(Estado e) { return new Resultado(e, 0); }
        static Resultado ok(long seq) { return new Resultado(Estado.OK, seq); }
    }

    /** Transferencia atomica de origen->destino por montoCentavos. */
    public Resultado transferir(int origenId, int destinoId, long montoCentavos) {
        if (origenId == destinoId) return Resultado.de(Estado.MISMA_CUENTA);
        if (montoCentavos <= 0)    return Resultado.de(Estado.MONTO_INVALIDO);

        Cuenta origen = cuentas.get(origenId);
        Cuenta destino = cuentas.get(destinoId);
        if (origen == null || destino == null) return Resultado.de(Estado.NO_ENCONTRADA);

        // Bloqueo en orden de id (menor primero) para no provocar deadlock. El
        // lock compartido del snapshot envuelve el par debito+credito (se toma
        // ANTES de los locks de cuenta y se suelta DESPUES) para que el escaneo
        // del total nunca caiga en medio del movimiento.
        Cuenta primero = origenId < destinoId ? origen : destino;
        Cuenta segundo = origenId < destinoId ? destino : origen;
        long stamp = snapshotLock.readLock();
        primero.lock.lock();
        segundo.lock.lock();
        try {
            if (origen.getSaldoCentavos() < montoCentavos) {
                return Resultado.de(Estado.SALDO_INSUFICIENTE);
            }
            origen.setSaldoCentavos(origen.getSaldoCentavos() - montoCentavos);
            destino.setSaldoCentavos(destino.getSaldoCentavos() + montoCentavos);
            // Tramo serializado: garantiza orden de insercion en registro == orden de seq.
            seqLock.lock();
            try {
                long seq = secuencia.incrementAndGet();
                totalTransferencias.increment();
                ultimaTxId = seq;
                Transaccion tx = new Transaccion(seq, origenId, destinoId, montoCentavos);
                registro.agregar(tx);
                observador.accept(tx);
                return Resultado.ok(seq);
            } finally {
                seqLock.unlock();
            }
        } finally {
            segundo.lock.unlock();
            primero.lock.unlock();
            snapshotLock.unlockRead(stamp);
        }
    }

    /**
     * Restaura los contadores tras cargar un punto de control de la replica:
     * fija la secuencia, la ultima tx y el total de transferencias. El total
     * == seq porque cada transferencia exitosa incrementa ambos 1:1 y sin
     * huecos. Solo se usa al arrancar una replica, ANTES de iniciar el cliente
     * de replicacion (que pedira RESUME desde esta secuencia).
     */
    public void restaurarSecuencia(long seq) {
        secuencia.set(seq);
        ultimaTxId = seq;
        totalTransferencias.reset();
        totalTransferencias.add(seq);
    }

    /**
     * Aplica una transaccion replicada del lider (ya validada alla): mueve el
     * monto, registra la tx y avanza la secuencia local a t.seq().
     */
    public void aplicarReplica(Transaccion t) {
        Cuenta origen = cuentas.get(t.origenId());
        Cuenta destino = cuentas.get(t.destinoId());
        if (origen == null || destino == null) return;

        Cuenta primero = t.origenId() < t.destinoId() ? origen : destino;
        Cuenta segundo = t.origenId() < t.destinoId() ? destino : origen;
        long stamp = snapshotLock.readLock();
        primero.lock.lock();
        segundo.lock.lock();
        try {
            origen.setSaldoCentavos(origen.getSaldoCentavos() - t.montoCentavos());
            destino.setSaldoCentavos(destino.getSaldoCentavos() + t.montoCentavos());
            registro.agregar(t);
            totalTransferencias.increment();
            ultimaTxId = t.seq();
            secuencia.accumulateAndGet(t.seq(), Math::max);
        } finally {
            segundo.lock.unlock();
            primero.lock.unlock();
            snapshotLock.unlockRead(stamp);
        }
    }
}
