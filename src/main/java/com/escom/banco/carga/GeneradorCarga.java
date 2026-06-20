package com.escom.banco.carga;

import com.escom.banco.json.Json;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * Generador de carga del PDF: golpea al nodo lider 1 minuto con 80% lecturas /
 * 20% transferencias concurrentes y verifica que la suma total de saldos no
 * cambie (invariante de conservacion). Java puro, sin dependencias nuevas.
 *
 * Uso: java -cp banco.jar com.escom.banco.carga.GeneradorCarga <host> <puerto> [segundos] [hilos]
 */
public final class GeneradorCarga {

    /** Parametros de una corrida. */
    public record Config(String host, int puerto, int segundos, int hilos, long montoMaxCent) {}

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

    // Un solo cliente HTTP/1.1 con keep-alive, reutilizado por todos los hilos.
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

        Thread[] hilos = new Thread[cfg.hilos()];
        long fin = System.nanoTime() + cfg.segundos() * 1_000_000_000L;
        for (int i = 0; i < hilos.length; i++) {
            hilos[i] = new Thread(() -> trabajar(fin, cuentas), "carga-" + i);
            hilos[i].start();
        }
        for (Thread t : hilos) t.join();

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

    /** Bucle de un hilo: 80% lecturas, 20% transferencias hasta la fecha limite. */
    private void trabajar(long finNanos, int cuentas) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        while (System.nanoTime() < finNanos) {
            try {
                if (rnd.nextInt(100) < 80) {
                    leer(1 + rnd.nextInt(cuentas));
                } else {
                    int origen = 1 + rnd.nextInt(cuentas);
                    int destino = 1 + rnd.nextInt(cuentas);
                    long montoCent = 1 + rnd.nextLong(cfg.montoMaxCent());
                    transferir(origen, destino, montoCent);
                }
            } catch (Exception e) {
                // Una peticion suelta puede fallar bajo carga; no abortamos la corrida.
            }
        }
    }

    private void leer(int id) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/accounts/" + id))
                .header("Authorization", autorizacion)
                .GET().build();
        int sc = http.send(req, BodyHandlers.discarding()).statusCode();
        if (sc == 200) lecturas.increment();
    }

    private void transferir(int origen, int destino, long montoCent) throws Exception {
        String body = "{\"sourceAccountId\":\"" + origen
                + "\",\"targetAccountId\":\"" + destino
                + "\",\"amount\":" + centavosAJson(montoCent) + "}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/transactions/transfer"))
                .header("Authorization", autorizacion)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        int sc = http.send(req, BodyHandlers.discarding()).statusCode();
        if (sc == 200) transOk.increment();
        else transFallidas.increment(); // saldo insuficiente, misma cuenta, etc.
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
            System.err.println("Uso: GeneradorCarga <host> <puerto> [segundos=60] [hilos=100]");
            System.exit(2);
        }
        String host = args[0];
        int puerto = Integer.parseInt(args[1]);
        int segundos = args.length > 2 ? Integer.parseInt(args[2]) : 60;
        int hilos = args.length > 3 ? Integer.parseInt(args[3]) : 100;

        Config cfg = new Config(host, puerto, segundos, hilos, 10000);
        System.out.printf("Cargando %s:%d  %ds  %d hilos  (80%% lecturas / 20%% transferencias)%n",
                host, puerto, segundos, hilos);

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
