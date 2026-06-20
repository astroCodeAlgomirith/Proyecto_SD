# Proyecto_SD - Mini Banco Concurrente Distribuido

Sistemas Distribuidos | Equipo 17 | Java puro (sin Spring).

## Estado

**Fase 0** - slice vertical de un solo nodo con los 4 endpoints del PDF, datos
en memoria, dinero en centavos y autenticacion JWT.

## Generar la base de datos (alumnos.csv)

```bash
cd herramientas
javac --release 17 GeneradorRegistros.java
java GeneradorRegistros          # produce alumnos.csv (820,000 filas, semilla fija)
mv alumnos.csv ../               # el nodo lo lee desde la raiz del proyecto
```

## Compilar y ejecutar

```bash
mvn -q package                   # genera target/banco.jar (fat jar)
java -jar target/banco.jar 8080  # carga el CSV y levanta el nodo
```

Variables de entorno:
- `ALUMNOS_CSV` - ruta del CSV (por defecto `./alumnos.csv`).
- `BANCO_JWT_SECRET` - secreto JWT compartido por los 3 nodos.

## Endpoints

| Metodo | Ruta | JWT | Cuerpo / respuesta |
|---|---|---|---|
| POST | `/api/register` | no | `{"username","password"}` -> 201 |
| POST | `/api/login` | no | `{"username","password"}` -> `{"token"}` |
| GET | `/api/accounts/{id}` | si | `{"id":125,"propietario":"...","balance":15750.25}` |
| POST | `/api/transactions/transfer` | si | `{"sourceAccountId":"1","targetAccountId":"2","amount":200.00}` |

## Convenciones

- `id` de cuenta = num. de linea de `alumnos.csv`, **base 1** (la fila 1 es id=1).
- Dinero interno en **centavos (long)**; el JSON expone decimales.
- Codigo en ASCII, sin Unicode. Menos de 400 LOC por archivo.
- Sin dependencias externas ajenas a las ya usadas (jbcrypt, java-jwt, gson).
