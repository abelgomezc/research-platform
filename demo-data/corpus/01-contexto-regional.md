# Contexto regional: deteccion de fraude en fintech latinoamericanas

> Documento sintetico de demostracion. Las cifras son inventadas.

## Alcance

Las fintech latinoamericanas operan en un contexto que combina alto volumen de
transacciones, inclusion financiera y presion de fraude. La digitalizacion de los
medios de pago crecio de forma sostenida en la region, y con ella crecio tambien
el fraude asociado a canales digitales.

## Caracteristicas del entorno

El sector se caracteriza por tres rasgos que condicionan cualquier solucion de
deteccion:

1. **Volumen y dispersion geografica.** Las operaciones llegan desde seis paises
   con monedas distintas, horarios distintos y estructuras de costo distintas.
   Un umbral unico produce demasiados falsos positivos en un pais y demasiados
   falsos negativos en otro.

2. **Economia de escalas de commision.** El margen por transaccion es pequeño. Un
   falso positivo cuesta una atencion de cliente y una compensacion. El costo de
   revisar un caso manualmente no es trivial, lo que obliga a priorizar.

3. **Adopcion de canal movil.** La mayoria de las transacciones del sector
   provienen de aplicacion movil, no de canal web ni de cajero automatico.

## Metricas de referencia del sector

En el conjunto de datos de demostracion de esta plataforma se registran 1200
transacciones sinteticas a lo largo de 2025, de las cuales 180 aparecen marcadas
como fraude. Eso representa una tasa del 15 por ciento, muy por encima de la tasa
real del sector, y existe a proposito: un corpus con muy pocos casos positivos
dificultaria que los casos de prueba observen algo.

## Tecnologias evaluadas

Cinco familias de tecnologia concentran la practica del sector:

- Reglas estaticas de umbrales y patrones conocidos.
- Modelos de machine learning supervisados.
- Analisis de grafo de relaciones entre cuentas.
- Biometria conductual del usuario.
- Deteccion de anomalias no supervisada.

Los documentos siguientes cubren cada una en detalle, con su effectiveness
declarada y sus limites.

## Advertencia sobre los datos

Este documento describe un conjunto sintetico creado para demostracion. Ninguna
cifra de este corpus debe interpretarse como una medicion del sector real.
