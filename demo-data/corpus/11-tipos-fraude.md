# Tipos de fraude en fintech latinoamericanas

> Documento sintetico de demostracion. Las cifras son inventadas.

## Catalogo

El dataset de demostracion registra doce incidentes sinteticos en la tabla
`demo_incidentes`, distribuidos en cinco tipos de fraude.

| Tipo de fraude | Incidentes | Monto promedio |
|---|---|---|
| Apropiacion de cuenta | 4 | 143450 USD |
| Punto de venta | 3 | 39300 USD |
| Phishing | 2 | 125200 USD |
| Sim Swap | 2 | 69900 USD |
| Lavado de activos | 2 | 226750 USD |

El monto promedio mas alto corresponde al lavado de activos y el mas bajo al
fraude en punto de venta. La diferencia es de casi cinco veces, lo que sugiere
que priorizar por monto esperado es distinto de priorizar por frecuencia.

## Apropiacion de cuenta

Es la categoria mas frecuente del conjunto. El atacante obtiene credenciales
validas y opera con la identidad de la victima. Los incidentes de esta categoria
se concentran en recuperacion de cuenta y sustitucion de SIM.

Deteccion: combinacion de reglas de umbral, modelo supervisado y analisis de
grafo. El analisis de grafo aporta valor aqui porque la apropiacion suele
incluir la incorporacion de un nuevo dispositivo o una nueva direccion de retiro,
seales que conectan la cuenta con otras.

## Punto de venta

Compras de alto valor en minutos, a veces desde el exterior del pais. Los tres
incidentes del conjunto tienen montos entre 28000 y 47500 USD y se concentran en
los canales POS y movil.

## Phishing

Suplantacion de la institucion para obtener credenciales. Los incidentes del
conjunto tienen montos de 96500 y 153900 USD.

## Sim Swap

Sustitucion de la tarjeta SIM para recibir el codigo de verificacion. Los dos
incidentes registrados tienen montos de 62800 y 77000 USD.

## Lavado de activos

Movimientos fraccionados bajo umbral y transferencias a intermediarios. Es la
categoria con mayor monto promedio del conjunto.

## Advertencia

Los doce incidentes son datos sinteticos inventados para esta demostracion.
No representan incidentes reales ni permiten comparar instituciones.
