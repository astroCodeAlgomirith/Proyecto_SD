package com.escom.banco.server;

import com.escom.banco.data.AlumnosLoader;
import com.escom.banco.data.CuentaRepository;
import com.escom.banco.replicacion.ClienteReplicacion;
import com.escom.banco.replicacion.ServidorReplicacion;
import com.sun.net.httpserver.HttpServer;

import java.nio.file.Path;

/**
 * Nodo del mini banco (Fase 0: un solo nodo, los 4 endpoints del PDF).
 * Uso: java -jar banco.jar [puerto]   (por defecto 8080)
 * CSV: variable de entorno ALUMNOS_CSV (por defecto ./alumnos.csv)
 */
public class MainServer {

    public static void main(String[] args) throws Exception {
        int puerto = 8080;
        if (args.length > 0) {
            try { puerto = Integer.parseInt(args[0]); }
            catch (NumberFormatException e) { System.err.println("Puerto invalido, usando 8080."); }
        }

        Path csv = Path.of(System.getenv().getOrDefault("ALUMNOS_CSV", "alumnos.csv"));
        System.out.println("Cargando cuentas desde " + csv.toAbsolutePath() + " ...");
        long t0 = System.nanoTime();
        int n = AlumnosLoader.cargar(CuentaRepository.get(), csv);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("Cargadas %,d cuentas en %d ms.%n", n, ms);

        String idLocal = System.getenv().getOrDefault("BANCO_NODO_ID", "nodo-" + puerto);
        java.util.List<String> peers = leerPeers();
        HttpServer server = BancoHttp.crear(puerto, idLocal, peers);
        server.start();

        System.out.println("Nodo del banco escuchando en http://0.0.0.0:" + puerto);
        System.out.println("  POST /api/register");
        System.out.println("  POST /api/login");
        System.out.println("  GET  /api/accounts/{id}            (JWT)");
        System.out.println("  POST /api/transactions/transfer    (JWT)");
        System.out.println("  GET  /stats");
        System.out.println("  GET  /         (panel) y /panel (JSON agregado)");
        if (!peers.isEmpty()) System.out.println("  Peers del panel: " + peers);

        iniciarReplicacion();
    }

    /** Peers del panel (host:port HTTP de los otros nodos) desde BANCO_PEERS. */
    private static java.util.List<String> leerPeers() {
        String env = System.getenv("BANCO_PEERS");
        if (env == null || env.isBlank()) return java.util.List.of();
        java.util.List<String> lista = new java.util.ArrayList<>();
        for (String p : env.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) lista.add(t);
        }
        return lista;
    }

    /**
     * Rol por variables de entorno: sin BANCO_LIDER_HOST -> LIDER (sirve el log);
     * con BANCO_LIDER_HOST -> REPLICA (sigue al lider y aplica en vivo).
     */
    private static void iniciarReplicacion() throws Exception {
        int puertoRepl = Integer.parseInt(
                System.getenv().getOrDefault("BANCO_REPLICACION_PUERTO", "9090"));
        String liderHost = System.getenv("BANCO_LIDER_HOST");
        if (liderHost == null || liderHost.isBlank()) {
            new ServidorReplicacion(CuentaRepository.get(), puertoRepl).iniciar();
            System.out.println("Rol: LIDER - replicacion TCP en puerto " + puertoRepl);
        } else {
            new ClienteReplicacion(CuentaRepository.get(), liderHost, puertoRepl).iniciar();
            System.out.println("Rol: REPLICA - siguiendo a " + liderHost + ":" + puertoRepl);
        }
    }
}
