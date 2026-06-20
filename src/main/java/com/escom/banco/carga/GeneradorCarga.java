package com.escom.banco.carga;

import com.escom.banco.json.Json;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * Generador de carga del PDF: golpea al nodo lider 1 minuto con 80% lecturas /
 * 20% transferencias concurrentes y verifica que la suma total de saldos no
 * cambie (invariante de conservacion). Java puro, sin dependencias nuevas.
 *
 * Motor asincrono: en vez de un hilo por peticion (1 envio bloqueante a la vez),
 * mantiene una ventana de N peticiones EN VUELO con sendAsync + un semaforo. Asi
 * una sola maquina sostiene mucha mas concurrencia sin gastar N hilos del SO.
 *
 * Uso: java -cp banco.jar com.escom.banco.carga.GeneradorCarga <host> <puerto> [segundos] [enVuelo]
 */
public final class GeneradorCarga {

    /** Parametros de una corrida (enVuelo = peticiones concurrentes en vuelo). */
    public record Config(String host, int puerto, int segundos, int enVuelo, long montoMaxCent) {}

    /** Reporte de una corrida. */
    public static final class Resultado {
        public long lecturas;
        public long transOk;
        public long transFallidas;
        public long saldoInicialCent;
        public long saldoFinalCent;
        public int cuentas;
        public boolean invarianteOk() { return saldoInicialCent == saldoFinalCent; }
        public long deltaCent() { return saldoFinalCent - saldoInicialCent; }
    }

    // Un solo cliente HTTP/1.1 con keep-alive, compartido por todas las peticiones.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final Config cfg;
    private final String base;
    private String autorizacion; // "Bearer <token>", se reusa en cada peticion

    private final LongAdder lecturas = new LongAdder();
    private final LongAdder transOk = new LongAdder();
    private final LongAdder transFallidas = new LongAdder();

    public GeneradorCarga(Config cfg) {
        this.cfg = cfg;
        this.base = "http://" + cfg.host() + ":" + cfg.puerto();
    }

    public Resultado ejecutar() throws Exception {
        autenticar();
        int cuentas = leerStats().get("cuentas").getAsInt();
        if (cuentas < 2) throw new IllegalStateException("El nodo no tiene cuentas cargadas.");
        long saldoInicial = leerStats().get("saldoTotalCentavos").getAsLong();

        int ventana = Math.max(1, cfg.enVuelo());
        Semaphore permisos = new Semaphore(ventana);
        long fin = System.nanoTime() + cfg.segundos() * 1_000_000_000L;

        while (System.nanoTime() < fin) {
            permisos.acquire();
            if (System.nanoTime() >= fin) { permisos.release(); break; }
            lanzarUna(cuentas, permisos);
        }
        // Esperar a que drenen las peticiones en vuelo (recuperar todos los permisos).
        permisos.acquire(ventana);

        long saldoFinal = leerStats().get("saldoTotalCentavos").getAsLong();

        Resultado r = new Resultado();
        r.lecturas = lecturas.sum();
        r.transOk = transOk.sum();
        r.transFallidas = transFallidas.sum();
        r.saldoInicialCent = saldoInicial;
        r.saldoFinalCent = saldoFinal;
        r.cuentas = cuentas;
        return r;
    }

    /** Lanza una peticion asincrona (80% lectura, 20% transferencia) y libera el permiso al terminar. */
    private void lanzarUna(int cuentas, Semaphore permisos) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        HttpRequest req;
        boolean lectura = rnd.nextInt(100) < 80;
        if (lectura) {
            int id = 1 + rnd.nextInt(cuentas);
            req = HttpRequest.newBuilder(URI.create(base + "/api/accounts/" + id))
                    .header("Authorization", autorizacion).GET().build();
        } else {
            int origen = 1 + rnd.nextInt(cuentas);
            int destino = 1 + rnd.nextInt(cuentas);
            long montoCent = 1 + rnd.nextLong(cfg.montoMaxCent());
            String body = "{\"sourceAccountId\":\"" + origen
                    + "\",\"targetAccountId\":\"" + destino
                    + "\",\"amount\":" + centavosAJson(montoCent) + "}";
            req = HttpRequest.newBuilder(URI.create(base + "/api/transactions/transfer"))
                    .header("Authorization", autorizacion)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        }
        http.sendAsync(req, BodyHandlers.discarding())
                .thenAccept(resp -> contar(lectura, resp.statusCode()))
                .whenComplete((v, e) -> permisos.release());
    }

    private void contar(boolean lectura, int sc) {
        if (lectura) {
            if (sc == 200) lecturas.increment();
        } else {
            if (sc == 200) transOk.increment(); else transFallidas.increment();
        }
    }

    /** centavos -> "123.45" sin usar double (evita perder exactitud). */
    private static String centavosAJson(long c) {
        return java.math.BigDecimal.valueOf(c, 2).toPlainString();
    }

    private void autenticar() throws Exception {
        String cred = "{\"username\":\"carga\",\"password\":\"carga123\"}";
        // register: 201 si es nuevo, 409 si ya existia; ambos sirven.
        post("/api/register", cred);
        String resp = post("/api/login", cred);
        autorizacion = "Bearer " + Json.parse(resp).get("token").getAsString();
    }

    private JsonObject leerStats() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/stats")).GET().build();
        return Json.parse(http.send(req, BodyHandlers.ofString()).body());
    }

    private String post(String ruta, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + ruta))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, BodyHandlers.ofString()).body();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Uso: GeneradorCarga <host> <puerto> [segundos=60] [enVuelo=512]");
            System.exit(2);
        }
        String host = args[0];
        int puerto = Integer.parseInt(args[1]);
        int segundos = args.length > 2 ? Integer.parseInt(args[2]) : 60;
        int enVuelo = args.length > 3 ? Integer.parseInt(args[3]) : 512;

        Config cfg = new Config(host, puerto, segundos, enVuelo, 10000);
        System.out.printf("Cargando %s:%d  %ds  %d en vuelo  (80%% lecturas / 20%% transferencias)%n",
                host, puerto, segundos, enVuelo);

        Resultado r = new GeneradorCarga(cfg).ejecutar();

        System.out.println("---------------------------------------------");
        System.out.printf("Cuentas en el nodo .......... %,d%n", r.cuentas);
        System.out.printf("Lecturas exitosas ........... %,d%n", r.lecturas);
        System.out.printf("Transferencias exitosas ..... %,d%n", r.transOk);
        System.out.printf("Transferencias rechazadas ... %,d%n", r.transFallidas);
        System.out.printf("Saldo total inicial ......... %s%n", centavosAJson(r.saldoInicialCent));
        System.out.printf("Saldo total final ........... %s%n", centavosAJson(r.saldoFinalCent));
        if (r.invarianteOk()) {
            System.out.println("INVARIANTE OK: la suma total se conservo exacta.");
        } else {
            System.out.printf("INCONSISTENCIA: el total cambio en %s (centavos: %d).%n",
                    centavosAJson(r.deltaCent()), r.deltaCent());
            System.exit(1);
        }
    }
}
