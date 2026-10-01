# Umbrales de transaccion por canal y pais

> Documento sintetico de demostracion. Las cifras son inventadas.

## Proposito

Este documento fija los umbrales operativos de referencia que usan los sistemas
de reglas del sector. Los valores se presentan como documentacion de referencia,
no como recomendacion calibrada para una institucion concreta.

## Umbrales por canal

| Canal | Umbral de rechazo | Ventana de agrupamiento | Maximo de operaciones |
|---|---|---|---|
| WEB | 5000 USD | 1 hora | 10 |
| MOVIL | 2000 USD | 24 horas | 30 |
| ATM | 1000 USD | 24 horas | 5 |
| POS | 3000 USD | 1 hora | 8 |
| API | 10000 USD | 10 minutos | 3 |

El canal API tiene el umbral monetario mas alto porque integra volumen
programatico, pero el limite de operaciones en ventana mas corto, porque un
abuso de integracion se manifiesta en rafagas.

## Umbrales por pais

El dataset de demostracion agrega seis paises: MX, CO, BR, CL, AR y PE. Para cada
uno de ellos se registra en la vista `demo_tasa_fraude_por_pais` el total de
transacciones, cuantas estuvieron marcadas como fraude, el score de riesgo
promedio y el tiempo de deteccion promedio en milisegundos.

La dispersion de la tasa de fraude entre paises es el argumento central a favor
de umbrales por segmento en lugar de un umbral global.

## Relacion con las reglas estaticas

Los umbrales de esta tabla son la implementacion concreta de lo que el documento
de reglas estaticas describe de forma general. El tiempo de deteccion promedio
declarado para las reglas en este conjunto de datos es de 95 milisegundos, que es
el valor mas bajo registrado entre las cinco tecnologias.
