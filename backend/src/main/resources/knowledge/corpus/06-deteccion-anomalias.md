# Deteccion de anomalias no supervisada

> Documento sintetico de demostracion. Las cifras son inventadas.

## Descripcion

Los modelos no supervisados no necesitan etiquetas. Buscan transacciones cuyo
perfil estadistico se aparta de la poblacion general. Son la respuesta natural al
problema de que el fraude detectado en el pasado no cubre los patrones
emergentes.

Tecnicas habituales:

- Deteccion de valores atipicos en el espacio de caracteristicas.
- Modelos de clustering aplicados a la distribucion de montos y horarios.
- Autoencoders que alertan cuando una transaccion no se parece a ninguna
  aprendida.

## Efectividad

En el conjunto de datos de demostracion, "Deteccion de anomalias" aparece en el
catalogo de tecnologias pero no figura como causa declarada en ninguna de las 180
transacciones marcadas como fraude. El tiempo de deteccion promedio declarado es
de 310 milisegundos.

Que no aparezca como causa en el dataset **no significa que sea inefectiva**.
Significa que en este conjunto de demostracion no fue la tecnologia que identifico
los casos marcados. Es una distincion que un sistema que verifica citas debe
respetar.

## Limitaciones

1. **Todo es anomalo hasta que se define el objetivo.** Sin una nocion de
   referencia, el modelo marca como raro lo raro y tambien lo erroneo.

2. **Tasa de falsos positivos alta.** La mayoria de anomalias estadisticas son
   combinaciones legitimas poco frecuentes.

3. **No explica.** Un puntaje de anomalia alto no indica por que la transaccion es
   inusual. Se necesita una capa posterior que la explique.

## Uso recomendado

Como capa de exploracion: la anomalias detectadas se convierten en candidatos que
pasan por un proceso de etiqueteo. Ese etiquetado retroalimenta despues al modelo
supervisado.
