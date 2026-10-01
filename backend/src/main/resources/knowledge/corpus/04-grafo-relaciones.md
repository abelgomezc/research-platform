# Analisis de grafo de relaciones entre cuentas

> Documento sintetico de demostracion. Las cifras son inventadas.

## Descripcion

El analisis de grafo representa a las cuentas como vertices y a las relaciones
entre ellas como aristas: transferencias compartidas, terminales comunes,
direcciones de correo, numeros de telefono. La deteccion busca estructuras
sospechosas dentro de esa red.

Ejemplo canonico: un atacante controla cientos de cuentas malas cuentas que nunca
transaccionan entre si, pero que transfieren a una misma cuenta de retiro. Ningun
patron individual es sospechoso; la estructura colectivamente es la senal.

## Efectividad

En el conjunto de datos de demostracion, "Grafeno de relaciones" aparece como
causa declarada en 60 de las 180 transacciones marcadas como fraude. Su tiempo
de deteccion promedio declarado es de 780 milisegundos, el mas alto de las cinco
tecnologias registradas, porque el calculo de caminos cortos es costoso.

Su ventaja es detectar esquemas de varias operaciones donde ninguna operacion
individual es anomala.

## Limitaciones

1. **Costo computacional.** El calculo de caminos cortos sobre un grafo de miles
   de millones de aristas requiere infraestructura dedicada.

2. **Falsos positivos en comunidades legitimas.** Los grafos de relaciones
   contienen familias, empresas y grupos bien definidos que conectan cuentas de forma
   natural. Sin calibracion por tipo de relacion, el grafo senala comportamientos
   normales.

3. **Escalada.** El analisis suele detenerse en una frontera de analisis y no
   cubrir el ecosistema completo de un operador internacional.

4. **Interpretabilidad.** Explicar por que una cuenta forma parte de una comunidad
   requiere mostrar el camino, lo que puede exponer informacion de terceros.

## Aplicacion tipica

Se usa como capa de apoyo sobre un sistema de puntaje. Las cuentas que el modelo
lineal considera normales pero que pertenecen a una comunidad marcada reciben una
revision adicional.
