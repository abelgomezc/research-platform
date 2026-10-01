# Reglas frente a machine learning: comparativa

> Documento sintetico de demostracion. Las cifras son inventadas.
>
> **Este documento contradice deliberadamente al documento 03.**

## Tesis sostenida en este documento

Este documento sostiene que **machine learning supera a las reglas estaticas en
deteccion**, y que las reglas son tecnologia heredada que deberia sustituirse.

Los argumentos son:

1. Las reglas solo detectan patrones ya catalogados. Cualquier esquema no
   catalogado pasa directamente.
2. El costo de mantener reglas escala mal, porque el numero de combinaciones
   crece de forma multiplicativa.
3. Las reglas producen falsos positivos constantes, lo que degrada la
   experiencia del cliente legitimo.
4. Un modelo entrenado sobre datos reales supera a cualquier conjunto de
   reglas que no se haya actualizado.

## Por que contradice al documento 03

El documento de machine learning afirma que el modelo tiene limitaciones
reales: depende de etiquetas, sufre deriva, es menos explicable y arrastra el
sesgo de sus datos de origen.

Este documento responde que esas limitaciones son **gestionales y no
tecnicas**, y que las reglas comparten las mismas limitaciones en mayor medida:

- Las reglas tambien requieren mantenimiento manual constante.
- Las reglas tambien tienen umbrales que envejecen.
- Las reglas tambien son opacas cuando interactuan entre si.
- Las reglas tambien producen falsos positivos.

La conclusion de este documento es que la comparacion entre reglas y modelos es
un falso dilema, y que la decision correcta depende del perfil de riesgo de cada
institucion.

## Nota para el sistema de verificacion

Este par de documentos existe para que el sistema de investigacion detecte una
contradiccion real entre dos fuentes verificables. Las dos afirmaciones no pueden
ser ciertas a la vez: o las reglas son tecnologia heredada a sustituir, o son la
primera linea que debe mantenerse.
