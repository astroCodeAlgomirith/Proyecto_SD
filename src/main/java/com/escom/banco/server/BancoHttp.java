package com.escom.banco.server;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Executors;

/** Construye el HttpServer del nodo con sus contextos (lo comparten MainServer y las pruebas). */
public final class BancoHttp {

    private BancoHttp() {}

    /** Servidor solo con los endpoints del banco (sin panel); util en pruebas. */
    public static HttpServer crear(int puerto) throws IOException {
        return crear(puerto, "este-nodo", List.of());
    }

    /** Crea (sin arrancar) el servidor con los 4 endpoints del PDF + /stats + panel. */
    public static HttpServer crear(int puerto, String idLocal, List<String> peers) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(puerto), 0);
        AuthHandler authHandler = new AuthHandler();
        server.createContext("/api/register", authHandler);
        server.createContext("/api/login", authHandler);
        server.createContext("/api/accounts", new AccountHandler());
        server.createContext("/api/transactions/transfer", new TransferHandler());
        server.createContext("/stats", new StatsHandler());
        server.createContext("/panel", new PanelHandler(idLocal, peers));
        server.createContext("/", new PaginaHandler());
        server.setExecutor(Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors() * 2));
        return server;
    }
}
