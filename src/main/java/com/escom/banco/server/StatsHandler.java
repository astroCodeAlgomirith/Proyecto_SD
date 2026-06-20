package com.escom.banco.server;

import com.escom.banco.data.CuentaRepository;
import com.escom.banco.json.Json;
import com.escom.banco.server.http.Manejador;
import com.escom.banco.server.http.Respuesta;
import com.escom.banco.server.http.Solicitud;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /stats -> snapshot del nodo para el generador de carga y el panel.
 * Publico (sin JWT) porque es solo monitoreo de un solo nodo. El saldo total se
 * da en centavos (long exacto) para verificar el invariante en UNA peticion.
 */
public class StatsHandler implements Manejador {

    @Override
    public Respuesta manejar(Solicitud s) {
        if (!"GET".equalsIgnoreCase(s.metodo())) return Respuesta.sinCuerpo(405);
        return Respuesta.json(200, Json.toJson(snapshot()));
    }

    /** Estado del nodo como mapa ordenado (lo reusa PanelHandler para el agregado). */
    public static Map<String, Object> snapshot() {
        CuentaRepository repo = CuentaRepository.get();
        String lider = System.getenv("BANCO_LIDER_HOST");
        String rol = (lider == null || lider.isBlank()) ? "LIDER" : "REPLICA";

        long totalCent = repo.saldoTotalCentavos();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rol", rol);
        out.put("cuentas", repo.tamano());
        out.put("saldoTotalCentavos", totalCent);
        out.put("saldoTotal", BigDecimal.valueOf(totalCent, 2));
        out.put("transferencias", repo.totalTransferencias());
        out.put("ultimaTxId", repo.ultimaTxId());
        out.put("secuencia", repo.secuenciaActual());
        out.put("cpu", SistemaMetricas.cpuPorcentaje());
        out.put("ram", SistemaMetricas.ramPorcentaje());
        out.put("disco", SistemaMetricas.discoPorcentaje());
        out.put("txEnStorage", repo.txEnStorage()); // -1 si el log GCS esta deshabilitado
        return out;
    }
}
