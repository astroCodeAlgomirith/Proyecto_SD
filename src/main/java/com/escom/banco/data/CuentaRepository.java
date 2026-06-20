package com.escom.banco.data;

import com.escom.banco.model.Cuenta;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

    public void put(Cuenta c) { cuentas.put(c.id, c); }
    public Cuenta get(int id) { return cuentas.get(id); }
    public int tamano() { return cuentas.size(); }

    public long totalTransferencias() { return totalTransferencias.get(); }
    public long ultimaTxId() { return ultimaTxId; }
    public long secuenciaActual() { return secuencia.get(); }

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
            return Resultado.ok(seq);
        } finally {
            segundo.lock.unlock();
            primero.lock.unlock();
        }
    }
}
