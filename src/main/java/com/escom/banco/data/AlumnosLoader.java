package com.escom.banco.data;

import com.escom.banco.model.Cuenta;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Carga alumnos.csv (820,000 filas, formato nombre,ap1,ap2,saldo) a memoria.
 * El id se asigna por contador en ORDEN DE LECTURA empezando en 1.
 * OJO: el CSV NO tiene cabecera -> no se salta la primera fila (esa es id=1).
 */
public final class AlumnosLoader {

    private AlumnosLoader() {}

    public static int cargar(CuentaRepository repo, Path csv) throws IOException {
        int id = 0;
        try (BufferedReader br = Files.newBufferedReader(csv)) {
            String linea;
            while ((linea = br.readLine()) != null) {
                if (linea.isBlank()) continue;
                String[] c = linea.split(",", -1);
                if (c.length < 4) continue;
                id++; // base 1, en orden de lectura
                long centavos = new BigDecimal(c[3].trim())
                        .movePointRight(2)
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact();
                repo.put(new Cuenta(id, c[0].trim(), c[1].trim(), c[2].trim(), centavos));
            }
        }
        return id;
    }
}
