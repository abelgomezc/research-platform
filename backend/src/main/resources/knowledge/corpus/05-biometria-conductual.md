# Biometria conductual en deteccion de fraude

> Documento sintetico de demostracion. Las cifras son inventadas.

## Descripcion

La biometria conductual construye un perfil del usuario a partir de como usa el
servicio, no de quien es. Se registra la cadencia de tecleo, el patron de
desplazamiento en pantalla, los horarios habituales de conexion, los dispositivo
habituales y la secuencia tipica de navegacion.

El principio es que un atacante automatizado tiene un patron distinto al del
titular legitimo, incluso cuando conoce sus credenciales. El fraude de
apropiacion de cuenta suele delatar la sesion por su ritmo, no por sus datos.

## Efectividad

La biometria conductual no aparece como causa declarada en ninguna transaccion
del conjunto de datos de demostracion, porque en ese dataset la deteccion se
registra unicamente a nivel de transaccion y no de sesion. El tiempo de deteccion
promedio declarado para esta tecnologia es de 610 milisegundos.

## Limitaciones

1. **Almacenamiento de perfiles de comportamiento.** El perfil requiere guardar
   historico de interaccion del usuario, lo que plantea cuestiones de privacidad y
   de cumplimiento normativo.

2. **Sensibilidad al contexto.** Un cambio de dispositivo legitimo, una viaje o un
   cambio de USO generan falso positivos. La calibracion continua es obligatoria.

3. **Cold start.** Una cuenta nueva no tiene perfil, y el periodo de aprendizaje
   es precisamente el de mayor riesgo.

4. **Atacantes persistentes.** Un atacante con acceso sostenido construye un
   perfil conductual falso con el tiempo.

## Nota importante

Este corpus de demostracion **no contiene informacion sobre autenticacion
multifactor ni sobre verificacion en dos pasos**. Ninguna afirmacion sobre esa
tecnologia puede sostenerse con las fuentes disponibles.
