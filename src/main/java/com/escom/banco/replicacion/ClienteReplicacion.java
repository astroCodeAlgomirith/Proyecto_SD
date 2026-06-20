package com.escom.banco.replicacion;

import com.escom.banco.data.CuentaRepository;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Lado replica: se conecta al lider, le dice en que secuencia se quedo
 * ("RESUME N") y aplica en orden cada transaccion que recibe. Si la conexion
 * se cae, reconecta y reanuda desde su secuencia actual.
 */
public class ClienteReplicacion {

    private final CuentaRepository repo;
    private final String hostLider;
    private final int puerto;
    private volatile boolean activo = false;

    public ClienteReplicacion(CuentaRepository repo, String hostLider, int puerto) {
        this.repo = repo;
        this.hostLider = hostLider;
        this.puerto = puerto;
    }

    public void iniciar() {
        activo = true;
        Thread t = new Thread(this::correr, "cliente-replicacion");
        t.setDaemon(true);
        t.start();
    }

    public void detener() {
        activo = false;
    }

    private void correr() {
        while (activo) {
            try (Socket s = new Socket(hostLider, puerto);
                 BufferedReader in = new BufferedReader(
                         new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter out = new BufferedWriter(
                         new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {

                out.write("RESUME " + repo.secuenciaActual() + "\n");
                out.flush();

                String linea;
                while (activo && (linea = in.readLine()) != null) {
                    repo.aplicarReplica(CodecTransaccion.deLinea(linea));
                }
            } catch (IOException e) {
                // lider no disponible: se reintenta abajo
            }
            if (!activo) return;
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
        }
    }
}
