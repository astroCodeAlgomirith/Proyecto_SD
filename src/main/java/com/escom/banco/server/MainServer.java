package com.escom.banco.server;

import com.escom.banco.data.AlumnosLoader;
import com.escom.banco.data.CuentaRepository;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Executors;

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

        HttpServer server = HttpServer.create(new InetSocketAddress(puerto), 0);
        AuthHandler authHandler = new AuthHandler();
        server.createContext("/api/register", authHandler);
        server.createContext("/api/login", authHandler);
        server.createContext("/api/accounts", new AccountHandler());
        server.createContext("/api/transactions/transfer", new TransferHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("Nodo del banco escuchando en http://0.0.0.0:" + puerto);
        System.out.println("  POST /api/register");
        System.out.println("  POST /api/login");
        System.out.println("  GET  /api/accounts/{id}            (JWT)");
        System.out.println("  POST /api/transactions/transfer    (JWT)");
    }
}
