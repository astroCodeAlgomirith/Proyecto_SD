# Guia para redactar capitulos del manual tecnico

Cada capitulo es un FRAGMENTO `.tex` que el archivo principal incluye con
`\input`. **No** escribas `\documentclass`, `\usepackage`, `\begin{document}`
ni `\end{document}`. Empieza directo con `\section{...}`.

El paquete `caratula.sty` ya esta cargado por el documento principal y aporta
todo el tema. Comandos y entornos disponibles:

## Estructura
- `\section{Titulo}`, `\subsection{Titulo}`, `\subsubsection{Titulo}`.

## Codigo
- En linea: `\codigo{texto}` para clases, metodos, rutas y variables de entorno.
  Ej: `\codigo{CuentaRepository.transferir()}`, `\codigo{BANCO\_LIDER\_HOST}`.
- En bloque (verbatim, NO se escapa nada adentro):

```latex
\begin{lstlisting}[language=Java,caption={Pie del listado}]
public Respuesta manejar(Solicitud s) {
    return Respuesta.json(200, cuerpo);
}
\end{lstlisting}
```

  Usa `language=Java` para Java y `language=none` para JSON, bash, HTTP u otros.

## Cajas
```latex
\begin{nota} Un apunte util. \end{nota}
\begin{advertencia} Algo critico que no hay que olvidar. \end{advertencia}
\begin{consejo} Una buena practica. \end{consejo}
```

## Listas y tablas
```latex
\begin{itemize}\item uno \item dos\end{itemize}

\begin{tablaglab}{L{4cm} L{9cm}}
\thead{Columna} & \thead{Descripcion} \\ \midrule
metodo & Verbo HTTP. \\
path   & Ruta sin query. \\
\end{tablaglab}
```
`L{ancho}` = parrafo justificado, `C{ancho}` = centrado; tambien sirven `l c r`.

## Escape (en PROSA y argumentos, NUNCA dentro de lstlisting)
Caracteres especiales -> escribir: `\#` `\%` `\&` `\_` `\{` `\}` `\$`,
`\textbackslash{}` para barra invertida, `\textasciitilde{}` para `~`.
Dentro de `lstlisting` el contenido es verbatim: NO escapes nada ahi.

## Reglas de contenido
- Solo informacion REAL leida de los archivos fuente indicados.
- Cita cada componente con `\codigo{ruta/Archivo.java}` al introducirlo.
- Fragmentos CORTOS (5-20 lineas), copiados del archivo real, recortables con
  `// ...`. No inventes metodos, firmas ni APIs.
- Prosa tecnica y concisa en espanol. Explica QUE hace, COMO y POR QUE.
- Cada capitulo: un `\section` y de 2 a 4 `\subsection`.
