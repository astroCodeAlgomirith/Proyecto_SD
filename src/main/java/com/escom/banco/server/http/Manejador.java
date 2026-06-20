package com.escom.banco.server.http;

/**
 * Handler como funcion pura: recibe la Solicitud parseada y devuelve la
 * Respuesta. NO conoce el SocketChannel ni los interestOps; el reactor decide
 * si se ejecuta inline o en el pool worker segun como este marcada la ruta.
 */
@FunctionalInterface
public interface Manejador {
    Respuesta manejar(Solicitud s);
}
