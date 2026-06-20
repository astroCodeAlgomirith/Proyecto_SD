package com.escom.banco.server.nio;

import com.escom.banco.server.http.Ruteador;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servidor HTTP/1.1 sobre NIO que reemplaza com.sun.net.httpserver. Levanta N
 * reactores; con SO_REUSEPORT cada reactor tiene su propio ServerSocketChannel y
 * el kernel reparte las conexiones. Si SO_REUSEPORT no esta disponible (o N==1),
 * un solo acceptor reparte los canales aceptados round-robin entre los reactores.
 * Las rutas marcadas WORKER corren en un pool de hilos daemon (bcrypt, /panel).
 * API: iniciar() / puerto() / detener() (idempotente).
 */
public final class ServidorNio {

    private static final int BACKLOG = 1024;

    private final Ruteador ruteador;
    private final int numReactores;
    private final int hilosPool;
    private int puerto;

    private Reactor[] reactores;
    private Thread[] hilos;
    private ServerSocketChannel[] sscs;
    private ExecutorService pool;
    private boolean iniciado = false;

    public ServidorNio(int puerto, Ruteador ruteador, int numReactores, int hilosPool) {
        this.puerto = puerto;
        this.ruteador = ruteador;
        this.numReactores = Math.max(1, numReactores);
        this.hilosPool = Math.max(1, hilosPool);
    }

    public synchronized ServidorNio iniciar() throws IOException {
        if (iniciado) return this;
        pool = Executors.newFixedThreadPool(hilosPool, fabrica("banco-worker"));
        reactores = new Reactor[numReactores];
        for (int i = 0; i < numReactores; i++) reactores[i] = new Reactor(ruteador, pool);

        try {
            boolean reuseport = numReactores > 1 && soportaReuseport();
            if (numReactores == 1) {
                ServerSocketChannel s = abrir(puerto, false);
                puerto = puertoLocal(s);
                sscs = new ServerSocketChannel[]{s};
                reactores[0].conAcceptor(s, null);
            } else if (reuseport) {
                sscs = new ServerSocketChannel[numReactores];
                ServerSocketChannel s0 = abrir(puerto, true);
                puerto = puertoLocal(s0);
                sscs[0] = s0;
                reactores[0].conAcceptor(s0, null);
                for (int i = 1; i < numReactores; i++) {
                    ServerSocketChannel si = abrir(puerto, true);
                    sscs[i] = si;
                    reactores[i].conAcceptor(si, null);
                }
            } else {
                ServerSocketChannel s = abrir(puerto, false);
                puerto = puertoLocal(s);
                sscs = new ServerSocketChannel[]{s};
                reactores[0].conAcceptor(s, reactores); // acceptor unico reparte round-robin
            }
        } catch (IOException e) {
            limpiarParcial(); // cerrar selectores y sockets ya abiertos antes de propagar
            throw e;
        }

        hilos = new Thread[numReactores];
        for (int i = 0; i < numReactores; i++) {
            hilos[i] = new Thread(reactores[i], "banco-reactor-" + i);
            hilos[i].setDaemon(true);
            hilos[i].start();
        }
        iniciado = true;
        return this;
    }

    public int puerto() { return puerto; }

    public synchronized void detener() {
        if (!iniciado) return;
        try {
            for (Reactor r : reactores) r.detener();
            if (sscs != null) {
                for (ServerSocketChannel s : sscs) {
                    try { if (s != null) s.close(); } catch (IOException ignore) {}
                }
            }
            for (Thread h : hilos) {
                try { h.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        } finally {
            if (pool != null) pool.shutdownNow();
            iniciado = false;
        }
    }

    /** Cierra selectores y sockets ya abiertos cuando iniciar() falla a mitad. */
    private void limpiarParcial() {
        if (reactores != null) for (Reactor r : reactores) r.cerrarSelector();
        if (sscs != null) {
            for (ServerSocketChannel s : sscs) {
                try { if (s != null) s.close(); } catch (IOException ignore) {}
            }
        }
        if (pool != null) pool.shutdownNow();
    }

    private static ServerSocketChannel abrir(int puerto, boolean reuseport) throws IOException {
        ServerSocketChannel s = ServerSocketChannel.open();
        try {
            s.configureBlocking(false);
            s.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            if (reuseport) s.setOption(StandardSocketOptions.SO_REUSEPORT, true);
            s.bind(new InetSocketAddress(puerto), BACKLOG);
            return s;
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignore) {}
            throw e;
        }
    }

    private static int puertoLocal(ServerSocketChannel s) throws IOException {
        return ((InetSocketAddress) s.getLocalAddress()).getPort();
    }

    private static boolean soportaReuseport() {
        try (ServerSocketChannel s = ServerSocketChannel.open()) {
            return s.supportedOptions().contains(StandardSocketOptions.SO_REUSEPORT);
        } catch (IOException e) {
            return false;
        }
    }

    private static ThreadFactory fabrica(String nombre) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, nombre + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
