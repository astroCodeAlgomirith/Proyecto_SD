package com.escom.banco.server;

import com.escom.banco.data.AlumnosLoader;
import com.escom.banco.data.CuentaRepository;
import com.escom.banco.replicacion.ClienteReplicacion;
import com.escom.banco.replicacion.PuntoControl;
import com.escom.banco.replicacion.ServidorReplicacion;
import com.escom.banco.server.nio.ServidorNio;

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

        // El lider recupera el estado desde el log GCS ANTES de servir trafico y
        // de enganchar el observador (asi el replay no se reescribe en GCS).
        // La replica carga su punto de control local (si existe) tambien ANTES de
        // servir, para no mostrar saldos del CSV en /stats durante el arranque.
        boolean esLider = esLider();
        if (esLider) iniciarAlmacenGcs();
        else cargarPuntoControl();

        ServidorNio server = BancoHttp.crear(puerto, idLocal, peers);
        server.iniciar();

        System.out.println("Nodo del banco escuchando en http://0.0.0.0:" + puerto);
        System.out.println("  POST /api/register");
        System.out.println("  POST /api/login");
        System.out.println("  GET  /api/accounts/{id}            (JWT)");
        System.out.println("  POST /api/transactions/transfer    (JWT)");
        System.out.println("  GET  /stats");
        System.out.println("  GET  /         (panel) y /panel (JSON agregado)");
        if (!peers.isEmpty()) System.out.println("  Peers del panel: " + peers);

        iniciarReplicacion();

        // Los hilos del servidor NIO son daemon (para que los tests terminen sin
        // shutdown manual); por eso el hilo main debe bloquearse o el JVM saldria
        // en cuanto main() retorna, dejando los sockets sin atender.
        new java.util.concurrent.CountDownLatch(1).await();
    }

    /**
     * Log durable en Cloud Storage (solo lider). Se activa con BANCO_GCS_BUCKET.
     * Primero reaplica el log existente sobre el estado del CSV (recuperacion en
     * frio si fallaron todos los nodos) y luego engancha el observador para los
     * nuevos commits. Nota: las cuentas creadas via /api/register que no esten
     * en el CSV no se recuperan; la prueba del PDF usa las cuentas del dataset.
     */
    private static void iniciarAlmacenGcs() {
        String bucket = System.getenv("BANCO_GCS_BUCKET");
        if (bucket == null || bucket.isBlank()) {
            System.out.println("Log GCS deshabilitado (sin BANCO_GCS_BUCKET).");
            return;
        }
        CuentaRepository repo = CuentaRepository.get();
        com.escom.banco.almacen.AlmacenGcs almacen =
                new com.escom.banco.almacen.AlmacenGcs(
                        new com.escom.banco.almacen.GcsSubidor(bucket));

        long t0 = System.nanoTime();
        long recuperadas = almacen.recuperar(repo::aplicarReplica);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (recuperadas > 0) {
            System.out.printf("Recuperadas %,d tx del log GCS en %d ms (seq=%d).%n",
                    recuperadas, ms, repo.secuenciaActual());
        }

        almacen.iniciar(recuperadas);
        repo.setObservador(almacen::registrar);
        repo.setConteoStorage(almacen::escritos);
        // Drena la cola asincrona en un apagado ordenado (SIGTERM): una tx ya
        // ackeada (HTTP 200) puede seguir pendiente de subir a GCS y se perderia
        // del log durable si el JVM termina sin vaciar la cola.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> almacen.detener()));
        System.out.println("Log durable en GCS: bucket " + bucket);
    }

    // Punto de control local de la replica (creado en cargarPuntoControl).
    private static PuntoControl puntoControl;

    /**
     * Carga el punto de control local de la replica (solo replica). El profesor
     * mata el PROCESO del nodo (no destruye la instancia), asi que el disco
     * sobrevive: al revivir se restaura {secuencia + saldos} y el cliente pedira
     * RESUME desde esa secuencia en vez de borrar y descargar todo. Ruta por
     * BANCO_CHECKPOINT (debe persistir entre reinicios del proceso); periodo del
     * snapshot por BANCO_CHECKPOINT_SEG. Si no hay archivo o esta corrupto, se
     * arranca desde el CSV sin impedir el boot.
     */
    private static void cargarPuntoControl() {
        Path ruta = Path.of(System.getenv().getOrDefault("BANCO_CHECKPOINT", "checkpoint.bin"));
        long seg = 3;
        try { seg = Long.parseLong(System.getenv().getOrDefault("BANCO_CHECKPOINT_SEG", "3")); }
        catch (NumberFormatException ignored) {}
        puntoControl = new PuntoControl(ruta, seg);
        long seq = puntoControl.cargar(CuentaRepository.get());
        if (seq >= 0) {
            System.out.printf("Punto de control cargado: secuencia %d (saldos restaurados de %s).%n",
                    seq, ruta.toAbsolutePath());
        } else {
            System.out.println("Sin punto de control previo: la replica arranca desde el CSV.");
        }
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

    /** LIDER si no hay BANCO_LIDER_HOST; en otro caso REPLICA. */
    private static boolean esLider() {
        String liderHost = System.getenv("BANCO_LIDER_HOST");
        return liderHost == null || liderHost.isBlank();
    }

    /**
     * Rol por variables de entorno: sin BANCO_LIDER_HOST -> LIDER (sirve el log);
     * con BANCO_LIDER_HOST -> REPLICA (sigue al lider y aplica en vivo). El log
     * GCS del lider ya se inicio antes de servir trafico (ver main).
     */
    private static void iniciarReplicacion() throws Exception {
        int puertoRepl = Integer.parseInt(
                System.getenv().getOrDefault("BANCO_REPLICACION_PUERTO", "9090"));
        if (esLider()) {
            new ServidorReplicacion(CuentaRepository.get(), puertoRepl).iniciar();
            System.out.println("Rol: LIDER - replicacion TCP en puerto " + puertoRepl);
        } else {
            String liderHost = System.getenv("BANCO_LIDER_HOST");
            CuentaRepository repo = CuentaRepository.get();
            ClienteReplicacion cliente = new ClienteReplicacion(repo, liderHost, puertoRepl);
            if (puntoControl != null) {
                cliente.setPuntoControl(puntoControl);
                puntoControl.iniciar(repo);
                // Snapshot exacto en un apagado ordenado (SIGTERM): "detiene el proceso".
                Runtime.getRuntime().addShutdownHook(
                        new Thread(() -> puntoControl.guardarFinal(repo), "punto-control-final"));
            }
            cliente.iniciar();
            System.out.println("Rol: REPLICA - siguiendo a " + liderHost + ":" + puertoRepl);
        }
    }
}
