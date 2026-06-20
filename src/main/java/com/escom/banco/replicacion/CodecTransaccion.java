package com.escom.banco.replicacion;

import com.escom.banco.model.Transaccion;

/** Serializa una transaccion a una linea de texto ASCII y de vuelta. */
public final class CodecTransaccion {

    private CodecTransaccion() {}

    public static String aLinea(Transaccion t) {
        return t.seq() + "," + t.origenId() + "," + t.destinoId() + "," + t.montoCentavos();
    }

    public static Transaccion deLinea(String linea) {
        String[] c = linea.split(",");
        return new Transaccion(
                Long.parseLong(c[0].trim()),
                Integer.parseInt(c[1].trim()),
                Integer.parseInt(c[2].trim()),
                Long.parseLong(c[3].trim()));
    }
}
