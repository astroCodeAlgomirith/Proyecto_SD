package com.escom.banco.replicacion;

import com.escom.banco.data.CuentaRepository;
import com.escom.banco.model.Cuenta;
import com.escom.banco.model.Transaccion;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;

/**
 * Punto de control LOCAL de una replica: persiste {secuencia + saldos} en disco
 * para que, tras matar el proceso (la instancia NO se destruye, asi que el disco
 * sobrevive), la replica reanude desde su ultima secuencia y pida solo el delta
 * al lider ("RESUME X"), en vez de borrar todo y recargar desde cero.
 *
 * <p>Consistencia: en una replica el estado solo lo muta {@code aplicarReplica},
 * llamado desde el unico hilo de {@link ClienteReplicacion}. El snapshot se toma
 * bajo el mismo candado, asi {secuencia, saldos} es un par coherente. El candado
 * solo cubre la copia en memoria; el volcado a disco va fuera del candado.
 *
 * <p>NO es una base de datos: es un archivo plano. El log durable para la caida
 * total de los tres nodos sigue siendo Cloud Storage (AlmacenGcs, solo lider).
 */
public final class PuntoControl {

    /** "BPC1" en ASCII: marca de formato para descartar archivos ajenos. */
    private static final int MAGIA = 0x42504331;
    /** Tope defensivo del conteo de cuentas para no asignar arreglos absurdos
     *  ante un archivo corrupto (el dataset real son ~820k cuentas). */
    private static final int LIMITE_CUENTAS = 50_000_000;

    private final Path archivo;
    private final long periodoMs;
    // Excluye apply (hilo de replicacion) de la COPIA del snapshot.
    private final Object candado = new Object();
    // Serializa los VOLCADOS a disco: el hilo timer y el shutdown hook nunca
    // escriben el archivo a la vez (evita un .tmp entrelazado en SIGTERM).
    private final Object lockDisco = new Object();
    private volatile boolean activo = false;

    public PuntoControl(Path archivo, long periodoSeg) {
        this.archivo = archivo;
        // Acota ambos extremos: un periodo 0 o un overflow de *1000 (sleep
        // negativo -> IllegalArgumentException) mataria el hilo de snapshots.
        long seg = Math.max(1L, Math.min(periodoSeg, 86_400L));
        this.periodoMs = seg * 1000L;
    }

    /**
     * Carga el checkpoint si existe y es valido: restaura los saldos y la
     * secuencia en el repo. Devuelve la secuencia cargada, o -1 si no hay
     * archivo o esta corrupto (y el arranque sigue con el CSV + RESUME 0).
     * NUNCA lanza: un checkpoint malo jamas debe impedir arrancar.
     */
    public long cargar(CuentaRepository repo) {
        if (!Files.exists(archivo)) return -1;
        long seq;
        int[] ids;
        long[] saldos;
        // Se parsea TODO el archivo a buffers temporales ANTES de tocar el repo:
        // un cuerpo truncado (cabecera valida + body a medias) revienta aqui sin
        // dejar el repo mitad-CSV/mitad-checkpoint (lo que con RESUME 0 daria
        // saldos corruptos en silencio). Si algo falla, el repo queda intacto.
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(archivo)))) {
            if (in.readInt() != MAGIA) {
                System.err.println("Punto de control: formato invalido, se ignora.");
                return -1;
            }
            seq = in.readLong();
            int count = in.readInt();
            if (count < 0 || count > LIMITE_CUENTAS) {
                System.err.println("Punto de control: conteo invalido (" + count + "), se ignora.");
                return -1;
            }
            ids = new int[count];
            saldos = new long[count];
            for (int i = 0; i < count; i++) {
                ids[i] = in.readInt();
                saldos[i] = in.readLong();
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Punto de control: no se pudo cargar (" + e.getMessage()
                    + "); se arranca desde el CSV.");
            return -1;
        }
        for (int i = 0; i < ids.length; i++) {
            Cuenta c = repo.get(ids[i]);
            if (c != null) c.setSaldoCentavos(saldos[i]);
        }
        repo.restaurarSecuencia(seq);
        return seq;
    }

    /**
     * Aplica una transaccion replicada serializandola con la toma de snapshots
     * (mismo candado) para que {secuencia, saldos} siempre sea coherente.
     * Se llama SOLO desde el hilo de replicacion.
     */
    public void aplicar(CuentaRepository repo, Transaccion tx) {
        synchronized (candado) {
            repo.aplicarReplica(tx);
        }
    }

    /** Arranca el hilo que guarda un snapshot cada periodoSeg segundos. */
    public void iniciar(CuentaRepository repo) {
        activo = true;
        Thread hilo = new Thread(() -> bucle(repo), "punto-control");
        hilo.setDaemon(true);
        hilo.start();
    }

    private void bucle(CuentaRepository repo) {
        while (activo) {
            try {
                Thread.sleep(periodoMs);
            } catch (InterruptedException e) {
                return;
            }
            if (activo) guardar(repo);
        }
    }

    /** Snapshot inmediato y consistente (shutdown hook = punto exacto en SIGTERM). */
    public void guardarFinal(CuentaRepository repo) {
        activo = false;
        guardar(repo);
    }

    public void detener() {
        activo = false;
    }

    private void guardar(CuentaRepository repo) {
        // lockDisco serializa los volcados: timer y shutdown hook no escriben el
        // mismo .tmp a la vez. La copia en memoria va bajo 'candado' (sin IO).
        synchronized (lockDisco) {
            byte[] datos;
            synchronized (candado) {
                datos = serializar(repo);
            }
            escribir(datos);
        }
    }

    /**
     * Copia {secuencia + (id, saldo) de cada cuenta} a un buffer. Llamado bajo
     * 'candado', asi aplicarReplica no corre en paralelo y el par es coherente.
     */
    private byte[] serializar(CuentaRepository repo) {
        Collection<Cuenta> cuentas = repo.cuentas();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(16 + cuentas.size() * 12);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(MAGIA);
            out.writeLong(repo.secuenciaActual());
            out.writeInt(cuentas.size());
            for (Cuenta c : cuentas) {
                out.writeInt(c.id);
                out.writeLong(c.getSaldoCentavos());
            }
        } catch (IOException e) {
            return new byte[0]; // ByteArrayOutputStream no lanza; defensivo.
        }
        return bos.toByteArray();
    }

    /** Escritura atomica: archivo temporal + rename, para no dejar un checkpoint a medias. */
    private void escribir(byte[] datos) {
        if (datos.length == 0) return;
        Path tmp = archivo.resolveSibling(archivo.getFileName() + ".tmp");
        try {
            Files.write(tmp, datos);
            try {
                Files.move(tmp, archivo, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, archivo, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("Punto de control: no se pudo escribir (" + e.getMessage() + ").");
        }
    }
}
