# Costo y factibilidad de las plataformas de deteccion

> Documento sintetico de demostracion. Las cifras son inventadas.
>
> **Este documento contradice deliberadamente al documento 05.**

## Tesis sostenida en este documento

Este documento sostiene que **la biometria conductual no es costo-efectiva para
las fintech de tamano mediano**, y que la inversion en infraestructura de
comportamiento no se justifica frente a reglas bien calibradas.

Los argumentos son:

1. La captura de la telemetria de comportamiento requiere un SDK y un hook en la
   aplicacion movil, con impacto directo en las tiendas de aplicaciones.
2. El almacenamiento de perfiles conductuales durante años genera requisitos
   regulatorios que ninguna regla estatica produce.
3. El tiempo de deteccion declarado de 610 milisegundos es peor que el de
   machine learning (420 ms) y mucho peor que el de las reglas (95 ms).
4. El cold start de una cuenta sin perfil coincide con el periodo de mayor riesgo.

## Conclusion sostenida

La biometria conductual solo se justifica en instituciones con capacidad
tecnica para sostenerla y con un perfil de riesgo alto. Para la mediana del
sector, la recomendacion es no adoptarla.

## Por que contradice al documento 05

El documento de biometria conductual presenta la tecnologia como una capa de
deteccion de valor, y describe sus limitaciones como condiciones de operacion
manejables.

Este documento sostiene exactamente lo contrario: que las limitaciones son
estructurales y que la tecnologia no debe adoptarse en el caso de uso mas
frecuente del sector.

La contradiccion es real y esta planteada por los dos documentos.
