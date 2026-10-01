# Machine learning supervisado para deteccion de fraude

> Documento sintetico de demostracion. Las cifras son inventadas.

## Descripcion

Los modelos de machine learning supervisado aprenden la relacion entre las
caracteristicas de una transaccion y su etiqueta de fraude a partir de
historiales etiquetados. En lugar de aplicar un umbral fijo, calculan un puntaje
de riesgo continuo por transaccion.

Caracteristicas tipicas del modelo:

- Historial de la cuenta: antiguedad, numero de operaciones, ticket promedio.
- Comportamiento de gasto: desviacion respecto del perfil historico.
- Contexto de la transaccion: canal, franja horaria, geolocalizacion.
- Grafo de la cuenta: grado, caminos cortos hacia cuentas ya marcadas.

## Efectividad

En el conjunto de datos de demostracion, "Machine Learning" aparece como causa
declarada en 60 de las 180 transacciones marcadas como fraude. Su tiempo de
deteccion promedio declarado es de 420 milisegundos.

El modelo cubre patrones que las reglas no catalogan, a costa de un costo de
inferencia mayor y de una explicabilidad menor.

## Limitaciones

1. **Dependencia de etiquetas.** El modelo solo es tan bueno como las etiquetas
   del historial. El fraude no detectado nunca se convierte en ejemplo de
   entrenamiento, de modo que el sesgo se acumula.

2. **Deriva del concepto.** El perfil del fraude cambia y el modelo entrenado
   sobre datos antiguos pierde exactitud sin que nada lo indique.

3. **Explicabilidad.** Una institucion que no puede justificar ante un regulator
   por que rechazo una operacion tiene un problema. Por eso suelen acompanarse de
   tecnicas de explicabilidad por feature de mayor contribucion.

4. **Sesgo de los datos de origen.** Si históricamente se reviso mas a ciertos
   perfiles, el modelo aprende a sospechar de ellos.

## Requisitos operativos

El reentrenamiento periodico, la monitorizacion de la distribucion de puntajes y
la deteccion de deriva son parte del modelo, no tareas opcionales. Un modelo sin
monitorizacion se degrada en silencio.
