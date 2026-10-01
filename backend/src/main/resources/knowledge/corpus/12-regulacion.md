# Marco regulatorio aplicable

> Documento sintetico de demostracion. Ninguna afirmacion de este documento
> constituye asesoramiento legal.

## Alcance

Este documento describe, a alto nivel, las obligaciones que las fintech
latinoamericanas enfrentan al operar sistemas de deteccion de fraude. Las
referencias concretas se omiten de forma deliberada: un corpus de demostracion
no debe citar normativa con precision aparente.

## Deberes generales

Las obligaciones que se repiten en las jurisdicciones del corpus documental
incluyen:

1. **Proteccion de datos personales.** Los datos usados para detectar fraude
   pueden seguir siendo datos personales aunque el fin sea la seguridad. El
   tratamiento de riesgo no exime de los deberes de informacion y de conservacion.

2. **Deber de diligencia.** La institucion debe poder explicar por que una
   transaccion fue bloqueada o liberada. Esto tiene consecuencia operativa
   directa: los sistemas con baja explicabilidad generan costos de atencion.

3. **Notificacion de incidentes.** El bloqueo de una transaccion puede ser
   computable como incidente de seguridad segun la jurisdiccion.

4. **Consignacion de datos.** Algunas jurisdicciones exigen que las entidades
   reporten indicadores de tarjetas robadas y cuentas marcadas.

## Implicacion para el diseno de sistemas

Dos consecuencias practicas se derivan de lo anterior:

- **La explicabilidad deja de ser opcional.** Una institucion que no puede
  justificar un bloqueo tiene un problema con el deber de diligencia. Esto
  favorece a las reglas y a los modelos con explicabilidad por feature frente a
  los modelos opacos.

- **El perfilado de comportamiento tiene carga adicional.** La biometria
  conductual discutida en otro documento del corpus requiere una justificacion de
  proporcionalidad que las reglas estaticas no requieren.

## Advertencia

Este corpus **no contiene informacion sobre autenticacion multifactor**, sobre
verificacion en dos pasos ni sobre RMS. Cualquier afirmacion sobre esas
tecnologias no puede sostenerse con las fuentes disponibles.
