# Tiempos de deteccion por tecnologia

> Documento sintetico de demostracion. Las cifras son inventadas.

## Resumen

Este documento consolida los tiempos de deteccion declarados para cada tecnologia
en el conjunto de datos de demostracion de esta plataforma.

| Tecnologia | Transacciones marcadas | Tiempo de deteccion promedio |
|---|---|---|
| Reglas de umbral | 60 | 95 ms |
| Deteccion de anomalias | 0 | 310 ms |
| Machine Learning | 60 | 420 ms |
| Biometria conductual | 0 | 610 ms |
| Grafeno de relaciones | 60 | 780 ms |

## Lectura de la tabla

El tiempo de deteccion no mide la calidad de la deteccion, sino la latencia del
calculo. Las reglas ganan porque un umbral no requiere inferencia estadistica.

Que una tecnologia tenga tiempo de deteccion alto no significa que sea peor. El
grafeno de relaciones es el mas lento y es el unico que detecta esquemas donde
ninguna transaccion individual es anomala.

Que una tecnologia tenga cero transacciones marcadas no significa que no funcione.
La deteccion de anomalias y la biometria conductual no figuran como causa
declarada en ninguna transaccion de este conjunto. En el caso de la biometria la
explicacion es que opera a nivel de sesion y el dataset registra a nivel de
transaccion.

## Riesgo operativo

Un tiempo de deteccion alto combinado con un flujo transaccional rapido deja una
ventana de exposicion. Por eso las arquitecturas del sector resuelven con la
combinacion de capas rapidas y lentas: una capa barata que filtra lo obvio de
inmediato y una capa costosa que revisa lo que la primera dejo pasar.
