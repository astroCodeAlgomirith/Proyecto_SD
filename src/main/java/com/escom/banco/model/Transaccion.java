package com.escom.banco.model;

/** Una transferencia registrada con su numero de secuencia (orden total). */
public record Transaccion(long seq, int origenId, int destinoId, long montoCentavos) {
}
