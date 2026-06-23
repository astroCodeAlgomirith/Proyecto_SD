package com.escom.banco.data;

import com.escom.banco.model.Cuenta;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Almacén en memoria optimizado para máximo throughput.
 * Respeta la restricción de no usar SGBD (Fase 0).
 */
public class CuentaRepository {

    private static final CuentaRepository INSTANCIA = new CuentaRepository();
    public static CuentaRepository get() { return INSTANCIA; }

    private final ConcurrentHashMap<Integer, Cuenta> cuentas = new ConcurrentHashMap<>();

    private final AtomicLong secuencia = new AtomicLong(0);
    private final AtomicLong totalTransferencias = new AtomicLong(0);
    
    // Acumulador para el saldo total de la base de datos
    private final AtomicLong saldoTotalGlobalCentavos = new AtomicLong(0);

    public void put(Cuenta c) { 
        cuentas.put(c.id, c); 
        saldoTotalGlobalCentavos.addAndGet(c.getSaldoCentavos());
    }
    
    public Cuenta get(int id) { return cuentas.get(id); }
    public int tamano() { return cuentas.size(); }

    public long totalTransferencias() { return totalTransferencias.get(); }
    public long ultimaTxId() { return secuencia.get(); } 
    public long secuenciaActual() { return secuencia.get(); }

    /** * Optimización O(1): Devuelve el saldo total instantáneamente 
     * sin recorrer las 820,000 cuentas en cada petición del Dashboard.
     */
    public long saldoTotalCentavos() {
        return saldoTotalGlobalCentavos.get();
    }

    public enum Estado { OK, MISMA_CUENTA, MONTO_INVALIDO, NO_ENCONTRADA, SALDO_INSUFICIENTE }

    public static final class Resultado {
        public final Estado estado;
        public final long seq;
        private Resultado(Estado estado, long seq) { this.estado = estado; this.seq = seq; }
        static Resultado de(Estado e) { return new Resultado(e, 0); }
        static Resultado ok(long seq) { return new Resultado(Estado.OK, seq); }
    }

    public Resultado transferir(int origenId, int destinoId, long montoCentavos) {
        if (origenId == destinoId) return Resultado.de(Estado.MISMA_CUENTA);
        if (montoCentavos <= 0)    return Resultado.de(Estado.MONTO_INVALIDO);

        Cuenta origen = cuentas.get(origenId);
        Cuenta destino = cuentas.get(destinoId);
        if (origen == null || destino == null) return Resultado.de(Estado.NO_ENCONTRADA);

        // Bloqueo ordenado por ID para prevenir de manera absoluta los Deadlocks
        Cuenta primero = origenId < destinoId ? origen : destino;
        Cuenta segundo = origenId < destinoId ? destino : origen;
        
        primero.lock.lock();
        try {
            segundo.lock.lock();
            try {
                if (origen.getSaldoCentavos() < montoCentavos) {
                    return Resultado.de(Estado.SALDO_INSUFICIENTE);
                }
                
                // Modificación de saldos en memoria
                origen.setSaldoCentavos(origen.getSaldoCentavos() - montoCentavos);
                destino.setSaldoCentavos(destino.getSaldoCentavos() + montoCentavos);
                
                // NOTA: El saldo global no se modifica porque la suma total permanece constante.
                long seq = secuencia.incrementAndGet();
                totalTransferencias.incrementAndGet();
                
                return Resultado.ok(seq);
            } finally {
                segundo.lock.unlock();
            }
        } finally {
            primero.lock.unlock();
        }
    }
}
