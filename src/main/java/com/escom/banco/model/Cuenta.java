package com.escom.banco.model;

import java.math.BigDecimal;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cuenta del mini banco. El dinero se guarda SIEMPRE en centavos (long) para
 * que el invariante de suma constante se mantenga exacto (nunca double).
 */
public class Cuenta {

    public final int id;            // = num. de linea de alumnos.csv, base 1
    public final String nombre;
    public final String apellido1;
    public final String apellido2;

    private long saldoCentavos;

    // Lock por cuenta para transferencias concurrentes (se toma en orden de id).
    public final ReentrantLock lock = new ReentrantLock();

    public Cuenta(int id, String nombre, String apellido1, String apellido2, long saldoCentavos) {
        this.id = id;
        this.nombre = nombre;
        this.apellido1 = apellido1;
        this.apellido2 = apellido2;
        this.saldoCentavos = saldoCentavos;
    }

    public long getSaldoCentavos() { return saldoCentavos; }
    public void setSaldoCentavos(long c) { this.saldoCentavos = c; }

    /** "NOMBRE APELLIDO1 APELLIDO2" tal como lo espera el campo propietario. */
    public String propietario() {
        return nombre + " " + apellido1 + " " + apellido2;
    }

    /** Saldo como decimal exacto (centavos / 100) para el JSON de salida. */
    public BigDecimal saldoDecimal() {
        return BigDecimal.valueOf(saldoCentavos, 2);
    }
}
