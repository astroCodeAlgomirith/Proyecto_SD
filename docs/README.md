# Documentacion (LaTeX)

Portada reutilizable y fuentes para los documentos del Proyecto Final.

## Como usar la portada

En cualquier documento:

```latex
\documentclass[12pt]{article}
\usepackage{caratula}
\begin{document}
\portada{Documentación de\\Arquitectura}
        {Descripción corta del documento.}
% ... contenido ...
\end{document}
```

- Argumento 1: titulo del documento (admite `\\` para cortar lineas).
- Argumento 2: descripcion corta.

## Compilar

Requiere `lualatex` o `xelatex` (usa las fuentes de `fonts/`):

```bash
latexmk -lualatex archivo.tex      # o: latexmk -xelatex archivo.tex
```

## Datos del equipo

Viven en UN solo lugar: el bloque "datos del equipo" de `caratula.sty`
(integrantes, grupos, profesor, ciclo). Se editan ahi una vez y se reflejan
en la portada de todos los documentos.

## Archivos

- `caratula.sty` — paquete de la portada (estilo guinda, Plus Jakarta Sans).
- `portada-demo.tex` / `portada-demo.pdf` — ejemplo.
- `fonts/` — Plus Jakarta Sans y JetBrains Mono (licencia OFL).
