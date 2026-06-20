package com.escom.banco.server.http;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Peticion HTTP/1.1 ya parseada e inmutable. Reemplaza las lecturas que antes
 * se hacian sobre HttpExchange (metodo, path, headers, body). Las claves de los
 * headers vienen en minusculas para que header() sea case-insensitive.
 */
public final class Solicitud {

    private final String metodo;            // "GET", "POST"
    private final String path;              // "/api/accounts/125" (sin query ni fragment)
    private final Map<String, String> headers;
    private final byte[] cuerpo;            // bytes del body; arreglo vacio si no hay
    private final boolean clientePideCerrar;

    public Solicitud(String metodo, String path, Map<String, String> headers,
                     byte[] cuerpo, boolean clientePideCerrar) {
        this.metodo = metodo;
        this.path = path;
        this.headers = headers;
        this.cuerpo = cuerpo;
        this.clientePideCerrar = clientePideCerrar;
    }

    public String metodo() { return metodo; }
    public String path() { return path; }

    /** Lookup case-insensitive (la clave ya viene en minusculas); null si ausente. */
    public String header(String nombre) { return headers.get(nombre.toLowerCase()); }

    public byte[] bodyBytes() { return cuerpo; }
    public String bodyTexto() { return new String(cuerpo, StandardCharsets.UTF_8); }
    public boolean clientePideCerrar() { return clientePideCerrar; }
}
