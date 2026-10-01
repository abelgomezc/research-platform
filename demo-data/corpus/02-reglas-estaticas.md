# Reglas estaticas de deteccion de fraude

> Documento sintetico de demostracion. Las cifras son inventadas.

## Descripcion

Un sistema de reglas estaticas define condiciones deterministas que marcan una
transaccion como sospechosa. No aprende de los datos: aplica umbrales y patrones
escritos por el equipo de riesgo.

Ejemplos tipicos de reglas:

- Transaccion por encima de un monto maximo por canal.
- Numero de operaciones superior a un maximo en una ventana de tiempo.
- Transaccion desde un pais distinto al habitual del titular.
- Retiro en cajero dentro de una ventana posterior a un cambio de direccion.

## Efectividad

Las reglas estaticas tienen una precision alta sobre los patrones que ya se
conocen, y una capacidad de deteccion nula sobre los patrones que todavia no se
han catalogado. En el conjunto de demostracion, la tecnologia "Reglas de umbral" aparece como causa declarada en 60
de las 180 transacciones marcadas como fraude.

El tiempo de deteccion promedio declarado para esta tecnologia en el dataset de
demostracion es de 95 milisegundos, el mas bajo de las cinco tecnologias
registradas.

## Limitaciones

1. **Mantenimiento manual.** Cada patron nuevo requiere escribir y validar una
   regla. El tiempo de respuesta ante ataques nuevos es siempre mayor que el de
   un modelo entrenado.

2. **Explosion combinatoria.** El numero de reglas crece con el producto de las
   dimensiones: canal, pais, franja horaria, tipo de titular. La combinacion de
   reglas interactivas produce falsos positivos dificil de anticipar.

3. **Opacidad.** Una regla es explicable, pero el conjunto completo de reglas
   interactuando deja de serlo. Auditar por que se rechazo una transaccion
   concreta exige reproducir toda la cadena.

4. **Umbrales fijo.** El mismo umbral se aplica a todos los clientes, lo que
   ignora que el perfil de riesgo varia por segmento.

## Cuando conviene

Las reglas siguen siendo la primera linea: son baratas de ejecutar, altamente
explicables y detectan rapido los casos inequivocos. El patron habitual en la
industria es combinarlas con modelos que cubran lo que las reglas no alcanzan.
