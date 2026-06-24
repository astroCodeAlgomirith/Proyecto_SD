package com.escom.banco.replicacion;

import com.escom.banco.data.CuentaRepository;
import com.escom.banco.model.Transaccion;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Lado lider: por cada replica conectada le envia el log desde su secuencia
 * (catch-up) y luego sigue empujando cada transaccion nueva en orden de seq.
 * El orden lo garantiza el log (registro.desde), no el momento del append.
 */
public class ServidorReplicacion {

    private final CuentaRepository repo;
    private final int puertoPedido;
    private ServerSocket serverSocket;
    private volatile boolean activo = false;

    public ServidorReplicacion(CuentaRepository repo, int puerto) {
        this.repo = repo;
        this.puertoPedido = puerto;
    }

    public void iniciar() throws IOException {
        serverSocket = new ServerSocket(puertoPedido);
        activo = true;
        Thread t = new Thread(this::aceptar, "replicacion-accept");
        t.setDaemon(true);
        t.start();
    }

    public int getPuerto() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    public void detener() {
        activo = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }

    private void aceptar() {
        while (activo) {
            try {
                Socket s = serverSocket.accept();
                Thread t = new Thread(() -> atender(s), "replica-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!activo) return;
            }
        }
    }

    private void atender(Socket s) {
        try (Socket sock = s;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8))) {

            long enviado = leerResume(in.readLine());   // "RESUME N"
            // Si la replica pide reanudar MAS ADELANTE del estado actual del lider,
            // su checkpoint quedo adelantado: es el caso de caida TOTAL, donde el
            // lider se recupero del log GCS (rezagado) y "retrocedio" a una seq
            // menor. Su estado seria inconsistente -> ordenarle reconstruir la base.
            if (enviado > repo.secuenciaActual()) {
                out.write("RESET\n");
                out.flush();
                return;
            }
            while (activo && !sock.isClosed()) {
                List<Transaccion> nuevas = repo.registro().desde(enviado);
                if (nuevas.isEmpty()) {
                    Thread.sleep(1);
                    continue;
                }
                for (Transaccion tx : nuevas) {
                    out.write(CodecTransaccion.aLinea(tx));
                    out.write("\n");
                    enviado = tx.seq();
                }
                out.flush();
            }
        } catch (Exception e) {
            // replica desconectada: el hilo termina
        }
    }

    private long leerResume(String saludo) {
        if (saludo == null) return 0;
        String[] p = saludo.trim().split("\\s+");
        if (p.length == 2 && p[0].equals("RESUME")) {
            try { return Long.parseLong(p[1]); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }
}
