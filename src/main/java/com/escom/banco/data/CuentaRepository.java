package com.escom.banco.data;

import com.escom.banco.model.Cuenta;
import com.escom.banco.model.Transaccion;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Almacen en memoria de las cuentas (sin SGBD, como exige el PDF).
 * Las transferencias toman el lock de las dos cuentas en orden de id para
 * evitar deadlock, y la suma total se conserva exacta (centavos long).
 */
public class CuentaRepository {

    private static final CuentaRepository INSTANCIA = new CuentaRepository();
    public static CuentaRepository get() { return INSTANCIA; }

    private final ConcurrentHashMap<Integer, Cuenta> cuentas = new ConcurrentHashMap<>();

    // Num. de secuencia de transaccion (base de la replicacion y el log GCS).
    private final AtomicLong secuencia = new AtomicLong(0);
    private final AtomicLong totalTransferencias = new AtomicLong(0);
    private volatile long ultimaTxId = 0;
    private final RegistroTransacciones registro = new RegistroTransacciones();

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

    public long totalTransferencias() { return totalTransferencias.get(); }
    public long ultimaTxId() { return ultimaTxId; }
    public long secuenciaActual() { return secuencia.get(); }
    public RegistroTransacciones registro() { return registro; }

    /** Suma de todos los saldos en centavos (para verificar el invariante). */
    public long saldoTotalCentavos() {
        long total = 0;
        for (Cuenta c : cuentas.values()) total += c.getSaldoCentavos();
        return total;
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

        // Bloqueo en orden de id (menor primero) para no provocar deadlock.
        Cuenta primero = origenId < destinoId ? origen : destino;
        Cuenta segundo = origenId < destinoId ? destino : origen;
        primero.lock.lock();
        segundo.lock.lock();
        try {
            if (origen.getSaldoCentavos() < montoCentavos) {
                return Resultado.de(Estado.SALDO_INSUFICIENTE);
            }
            origen.setSaldoCentavos(origen.getSaldoCentavos() - montoCentavos);
            destino.setSaldoCentavos(destino.getSaldoCentavos() + montoCentavos);
            long seq = secuencia.incrementAndGet();
            totalTransferencias.incrementAndGet();
            ultimaTxId = seq;
            Transaccion tx = new Transaccion(seq, origenId, destinoId, montoCentavos);
            registro.agregar(tx);
            observador.accept(tx);
            return Resultado.ok(seq);
        } finally {
            segundo.lock.unlock();
            primero.lock.unlock();
        }
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
        primero.lock.lock();
        segundo.lock.lock();
        try {
            origen.setSaldoCentavos(origen.getSaldoCentavos() - t.montoCentavos());
            destino.setSaldoCentavos(destino.getSaldoCentavos() + t.montoCentavos());
            registro.agregar(t);
            totalTransferencias.incrementAndGet();
            ultimaTxId = t.seq();
            secuencia.accumulateAndGet(t.seq(), Math::max);
        } finally {
            segundo.lock.unlock();
            primero.lock.unlock();
        }
    }
}
