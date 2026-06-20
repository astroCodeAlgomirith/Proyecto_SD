package com.escom.banco.data;

import com.escom.banco.model.Transaccion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Log ordenado de transacciones indexado por numero de secuencia.
 * Es la base de la replicacion (catch-up) y de la recuperacion por secuencia.
 * ConcurrentSkipListMap mantiene el orden por seq aunque los appends concurran.
 */
public class RegistroTransacciones {

    private final ConcurrentSkipListMap<Long, Transaccion> log = new ConcurrentSkipListMap<>();

    public void agregar(Transaccion t) {
        log.put(t.seq(), t);
    }

    /** Transacciones con seq estrictamente mayor a la dada, en orden de seq. */
    public List<Transaccion> desde(long seq) {
        return new ArrayList<>(log.tailMap(seq, false).values());
    }

    public long ultimaSecuencia() {
        return log.isEmpty() ? 0 : log.lastKey();
    }

    public int tamano() {
        return log.size();
    }
}
