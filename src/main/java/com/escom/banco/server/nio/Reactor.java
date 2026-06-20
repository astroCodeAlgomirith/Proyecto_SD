package com.escom.banco.server.nio;

import com.escom.banco.server.http.Manejador;
import com.escom.banco.server.http.ParserHttp;
import com.escom.banco.server.http.Respuesta;
import com.escom.banco.server.http.Ruteador;
import com.escom.banco.server.http.Solicitud;

import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;

/**
 * Un hilo + un Selector. Bucle epoll: registra canales nuevos, drena la cola de
 * escritura de los workers, y atiende OP_ACCEPT/OP_READ/OP_WRITE. INVARIANTE: solo
 * este hilo toca el SocketChannel y key.interestOps(); los workers unicamente
 * encolan bytes en la Conexion + pendientesEscritura y hacen selector.wakeup().
 */
final class Reactor implements Runnable {

    private final Selector selector;
    private final Ruteador ruteador;
    private final ExecutorService pool;
    private ServerSocketChannel ssc;           // !=null si este reactor acepta
    private Reactor[] objetivos;               // !=null => modo acceptor unico: reparte round-robin
    private int rr = 0;

    private final Queue<SocketChannel> nuevos = new ConcurrentLinkedQueue<>();
    private final Queue<SelectionKey> pendientesEscritura = new ConcurrentLinkedQueue<>();
    private volatile boolean activo = true;

    Reactor(Ruteador ruteador, ExecutorService pool) throws IOException {
        this.ruteador = ruteador;
        this.pool = pool;
        this.selector = Selector.open();
    }

    void conAcceptor(ServerSocketChannel ssc, Reactor[] objetivos) throws IOException {
        this.ssc = ssc;
        this.objetivos = objetivos;
        ssc.register(selector, SelectionKey.OP_ACCEPT);
    }

    /** Recibe un canal aceptado por otro reactor (modo acceptor unico). */
    void recibir(SocketChannel ch) {
        nuevos.add(ch);
        selector.wakeup();
    }

    /** Marca una key como pendiente de flush (su salida ya fue encolada) y despierta al selector. */
    private void marcarPendiente(SelectionKey key) {
        pendientesEscritura.add(key);
        selector.wakeup();
    }

    void detener() {
        activo = false;
        selector.wakeup();
    }

    /** Cierra el selector sin haber arrancado el hilo (rollback de iniciar()). */
    void cerrarSelector() {
        try { selector.close(); } catch (IOException ignore) {}
    }

    @Override
    public void run() {
        try {
            while (activo) {
                SocketChannel ch;
                while ((ch = nuevos.poll()) != null) registrarCanal(ch);
                SelectionKey wk;
                while ((wk = pendientesEscritura.poll()) != null) {
                    if (wk.isValid()) flush(wk);
                }
                selector.select();
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) continue;
                    try {
                        if (key.isAcceptable()) { if (activo) aceptar(); }
                        else if (key.isReadable()) leer(key);
                        else if (key.isWritable()) flush(key);
                    } catch (CancelledKeyException e) {
                        // la key se cancelo concurrentemente; seguir
                    }
                }
            }
        } catch (IOException e) {
            if (activo) System.err.println("Reactor: " + e);
        } finally {
            // cierre limpio: canales vivos + canales aceptados aun sin registrar
            for (SelectionKey k : selector.keys()) {
                try { k.channel().close(); } catch (IOException ignore) {}
            }
            SocketChannel ch;
            while ((ch = nuevos.poll()) != null) {
                try { ch.close(); } catch (IOException ignore) {}
            }
            try { selector.close(); } catch (IOException ignore) {}
        }
    }

    private void aceptar() throws IOException {
        SocketChannel ch;
        while ((ch = ssc.accept()) != null) {
            if (objetivos != null && objetivos.length > 1) {
                objetivos[(rr++ & 0x7fffffff) % objetivos.length].recibir(ch);
            } else {
                registrarCanal(ch);
            }
        }
    }

    private void registrarCanal(SocketChannel ch) {
        try {
            ch.configureBlocking(false);
            ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
            ch.register(selector, SelectionKey.OP_READ, new Conexion(ch));
        } catch (IOException e) {
            try { ch.close(); } catch (IOException ignore) {}
        }
    }

    private void leer(SelectionKey key) {
        Conexion cx = (Conexion) key.attachment();
        try {
            if (!cx.asegurar(4096)) {
                cx.encolar(Conexion.serializar(Respuesta.sinCuerpo(431), true));
                cx.cerrarTrasFlush = true;
                flush(key);
                return;
            }
            ByteBuffer bb = ByteBuffer.wrap(cx.buf, cx.len, cx.buf.length - cx.len);
            int n = cx.canal.read(bb);
            if (n == -1) { cerrar(key); return; }
            if (n == 0) return;
            cx.len += n;
            procesar(key, cx);
            flush(key);
        } catch (IOException e) {
            cerrar(key);
        }
    }

    /** Parsea y despacha todas las requests completas presentes en el buffer. No escribe. */
    private void procesar(SelectionKey key, Conexion cx) {
        while (true) {
            ParserHttp.Resultado r = ParserHttp.parsear(cx.buf, cx.len);
            if (r.estado == ParserHttp.Estado.INCOMPLETA) {
                if (r.enviarContinue && !cx.continueEnviado) {
                    cx.encolar(Conexion.continue100());
                    cx.continueEnviado = true;
                }
                return;
            }
            if (r.estado == ParserHttp.Estado.ERROR) {
                cx.encolar(Conexion.serializar(Respuesta.sinCuerpo(r.codigoError), true));
                cx.cerrarTrasFlush = true;
                return;
            }
            Solicitud sol = r.solicitud;
            cx.compactar(r.bytesConsumidos);
            cx.continueEnviado = false;
            boolean cerrar = sol.clientePideCerrar();

            Ruteador.Ruta ruta = ruteador.resolver(sol.path());
            if (ruta == null) {
                cx.encolar(Conexion.serializar(Respuesta.error(404, "Recurso no encontrado"), cerrar));
                if (cerrar) { cx.cerrarTrasFlush = true; return; }
                continue;
            }
            if (ruta.worker) {
                cx.esperandoWorker = true;
                despacharWorker(key, cx, ruta.manejador, sol, cerrar);
                return; // el cliente no pipelinea: no parseamos mas hasta responder
            }
            Respuesta resp = ejecutar(ruta.manejador, sol);
            boolean cerrarFinal = cerrar || resp.cerrar;
            cx.encolar(Conexion.serializar(resp, cerrarFinal));
            if (cerrarFinal) { cx.cerrarTrasFlush = true; return; }
        }
    }

    private void despacharWorker(SelectionKey key, Conexion cx, Manejador m, Solicitud sol, boolean cerrar) {
        pool.execute(() -> {
            Respuesta resp = ejecutar(m, sol);
            boolean cerrarFinal = cerrar || resp.cerrar;
            cx.encolar(Conexion.serializar(resp, cerrarFinal));
            if (cerrarFinal) cx.cerrarTrasFlush = true;
            cx.esperandoWorker = false;
            marcarPendiente(key); // la respuesta ya esta en la cola; pedir flush + wakeup
        });
    }

    private static Respuesta ejecutar(Manejador m, Solicitud sol) {
        try { return m.manejar(sol); }
        catch (Exception e) { return Respuesta.error(500, "Error interno"); }
    }

    private void flush(SelectionKey key) {
        Conexion cx = (Conexion) key.attachment();
        SocketChannel ch = cx.canal;
        try {
            while (true) {
                ByteBuffer bb;
                while ((bb = cx.salida.peek()) != null) {
                    ch.write(bb);
                    if (bb.hasRemaining()) {
                        key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                        return;
                    }
                    cx.salida.poll();
                }
                if (cx.cerrarTrasFlush) { cerrar(key); return; }
                key.interestOps(SelectionKey.OP_READ);
                if (cx.len > 0 && !cx.esperandoWorker) {
                    procesar(key, cx);
                    if (!cx.salida.isEmpty()) continue; // hay respuestas nuevas que escribir
                }
                return;
            }
        } catch (IOException e) {
            cerrar(key);
        }
    }

    private void cerrar(SelectionKey key) {
        try { key.channel().close(); } catch (IOException ignore) {}
        key.cancel();
    }
}
