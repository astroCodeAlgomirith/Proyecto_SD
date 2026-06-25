package com.escom.banco.replicacion;

import com.escom.banco.data.CuentaRepository;
import com.escom.banco.model.Transaccion;

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
    // Opcional: lo usa la replica de produccion para persistir su avance en disco
    // y aplicar de forma consistente con los snapshots. null = comportamiento base.
    private volatile PuntoControl puntoControl;
    // Opcional: que hacer si el lider ordena RESET (recargar la base del CSV y
    // dejar la secuencia en 0). null = solo se ignora el RESET.
    private volatile Runnable alResetear;

    public ClienteReplicacion(CuentaRepository repo, String hostLider, int puerto) {
        this.repo = repo;
        this.hostLider = hostLider;
        this.puerto = puerto;
    }

    public void setPuntoControl(PuntoControl pc) { this.puntoControl = pc; }
    public void setAlResetear(Runnable r) { this.alResetear = r; }

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

                long seq = repo.secuenciaActual();
                // Deja constancia en el log de la replica al levantarla: confirma
                // que reanuda por secuencia (no borra y descarga todo de nuevo).
                System.out.println("Me quede en la secuencia " + seq
                        + ", enviame desde " + (seq + 1));
                out.write("RESUME " + seq + "\n");
                out.flush();

                PuntoControl pc = this.puntoControl;
                String linea;
                while (activo && (linea = in.readLine()) != null) {
                    if (linea.equals("RESET")) {
                        // El lider indica que nuestro checkpoint quedo ADELANTE de su
                        // estado durable (caida total: se recupero del log GCS
                        // rezagado). Reconstruir la base y reanudar desde 0 para no
                        // quedar divergente.
                        Runnable r = alResetear;
                        if (r != null) r.run();
                        break;
                    }
                    try {
                        Transaccion tx = CodecTransaccion.deLinea(linea);
                        if (pc != null) pc.aplicar(repo, tx);
                        else repo.aplicarReplica(tx);
                    } catch (RuntimeException ex) {
                        // Linea mal formada (p.ej. corte de conexion a media linea):
                        // no debe matar el hilo; se corta y se reconecta con RESUME.
                        break;
                    }
                }
            } catch (IOException e) {
                // lider no disponible: se reintenta abajo
            }
            if (!activo) return;
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
        }
    }
}
