import { useCallback, useEffect, useRef, useState } from 'react';
import { api, abrirStream } from '../api';
import type { ResearchDetail, ResearchEvent, ReportRow } from '../types';
import { estaActiva } from '../types';
import ListaEventos from './ListaEventos';
import MarkdownInforme from './MarkdownInforme';

interface Props {
  id: number;
  onVolver: () => void;
}

/**
 * Detalle de una investigacion con su progreso en vivo.
 *
 * <p>El stream se cierra solo cuando la investigacion deja de estar activa, y se
 * vuelve a consultar el detalle en ese momento: el evento puede llegar antes de
 * que la transaccion que lo produjo este confirmada.
 */
export default function DetalleInvestigacion({ id, onVolver }: Props) {
  const [detalle, setDetalle] = useState<ResearchDetail | null>(null);
  const [eventos, setEventos] = useState<ResearchEvent[]>([]);
  const [informe, setInforme] = useState<ReportRow | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [mensaje, setMensaje] = useState<string | null>(null);
  const [conectado, setConectado] = useState(false);
  const fuenteRef = useRef<EventSource | null>(null);

  const cargarDetalle = useCallback(async () => {
    try {
      setDetalle(await api.obtenerInvestigacion(id));
    } catch (excepcion) {
      setError(excepcion instanceof Error ? excepcion.message : 'No se pudo cargar el detalle');
    }
  }, [id]);

  const cargarInforme = useCallback(async () => {
    try {
      const respuesta = await api.informe(id);
      if (!Array.isArray(respuesta)) {
        setInforme(respuesta);
      }
    } catch {
      // Todavia no hay informe: no es un error que deba mostrarse.
    }
  }, [id]);

  useEffect(() => {
    void cargarDetalle();
    void cargarInforme();

    const fuente = abrirStream(
      id,
      (evento) => {
        setEventos((previos) => [...previos.slice(-199), evento]);

        if (evento.tipo === 'REPORT_GENERATED' || evento.tipo === 'REVIEW_COMPLETED') {
          void cargarInforme();
        }
        if (evento.tipo === 'ESTADO' || evento.tipo === 'FAILURE') {
          void cargarDetalle();
        }
      },
      () => setConectado(false),
    );

    fuenteRef.current = fuente;
    setConectado(true);

    // Sondeo suave: el estado y el informe se actualizan por evento, pero el
    // detalle tambien depende de cosas que no generan evento, como el consumo
    // de tokens.
    const intervalo = setInterval(() => {
      void cargarDetalle();
    }, 5000);

    return () => {
      clearInterval(intervalo);
      fuente.close();
      fuenteRef.current = null;
    };
  }, [id, cargarDetalle, cargarInforme]);

  async function cancelar() {
    try {
      const respuesta = await api.cancelar(id);
      setMensaje(respuesta.mensaje);
      await cargarDetalle();
    } catch (excepcion) {
      setError(excepcion instanceof Error ? excepcion.message : 'No se pudo cancelar');
    }
  }

  async function reanudar() {
    try {
      const respuesta = await api.reanudar(id);
      setMensaje(respuesta.mensaje);
      await cargarDetalle();
    } catch (excepcion) {
      setError(excepcion instanceof Error ? excepcion.message : 'No se pudo reanudar');
    }
  }

  if (!detalle) {
    return (
      <div className="app">
        <p className="vacio">
          <span className="spinner" />
          Cargando investigacion...
        </p>
        <button className="boton" onClick={onVolver}>
          Volver
        </button>
      </div>
    );
  }

  const consumo = detalle.presupuestoTokens > 0
    ? detalle.tokensConsumidos / detalle.presupuestoTokens
    : 0;

  return (
    <div className="app">
      <header className="cabecera">
        <button className="boton boton-texto" onClick={onVolver}>
          &larr; Volver
        </button>
        <div className={`estado estado-${detalle.estado.toLowerCase()}`}>
          {detalle.estado}
          {!conectado && estaActiva(detalle.estado) && ' (sin conexion)'}
        </div>
      </header>

      <main className="contenido">
        <section className="panel">
          <h1 className="titulo-objetivo">{detalle.objetivo}</h1>

          <div className="indicadores">
            <div className="indicador">
              <span className="indicador-etiqueta">Ronda</span>
              <span className="indicador-valor">
                {detalle.ronda} / {detalle.maxRondas}
              </span>
            </div>

            <div className="indicador">
              <span className="indicador-etiqueta">Presupuesto</span>
              <span className="indicador-valor">
                {detalle.tokensConsumidos.toLocaleString('es')} /{' '}
                {detalle.presupuestoTokens.toLocaleString('es')}
              </span>
              <div className="barra">
                <div
                  className={`barra-relleno ${consumo > 0.8 ? 'barra-alerta' : ''}`}
                  style={{ width: `${Math.min(100, consumo * 100)}%` }}
                />
              </div>
            </div>

            <div className="indicador">
              <span className="indicador-etiqueta">Evidencias</span>
              <span className="indicador-valor">
                {detalle.verificacion.verificadas} / {detalle.verificacion.total}
              </span>
              <span className="indicador-detalle">
                {detalle.verificacion.noVerificadas} descartadas,{' '}
                {detalle.verificacion.parciales} parciales
              </span>
            </div>

            <div className="indicador">
              <span className="indicador-etiqueta">Tareas</span>
              <span className="indicador-valor">{detalle.totalTareas}</span>
            </div>
          </div>

          {detalle.motivoFallo && <p className="aviso">{detalle.motivoFallo}</p>}

          <div className="acciones">
            {estaActiva(detalle.estado) && (
              <button className="boton boton-peligro" onClick={cancelar}>
                Cancelar
              </button>
            )}
            {detalle.estado === 'INTERRUPTED' && (
              <button className="boton boton-primario" onClick={reanudar}>
                Reanudar
              </button>
            )}
          </div>

          {mensaje && <p className="mensaje">{mensaje}</p>}
          {error && <p className="error">{error}</p>}
        </section>

        <section className="panel">
          <h2>Informe</h2>
          {informe ? (
            <>
              <div className="informe-meta">
                Version {informe.version} | {informe.estadoRevision}
              </div>
              <MarkdownInforme contenido={informe.contenidoMarkdown} />
              {informe.advertencias.length > 0 && (
                <div className="advertencias">
                  <h3>Advertencias</h3>
                  <ul>
                    {informe.advertencias.map((advertencia, indice) => (
                      <li key={indice}>{advertencia}</li>
                    ))}
                  </ul>
                </div>
              )}
            </>
          ) : (
            <p className="vacio">
              {estaActiva(detalle.estado)
                ? 'El informe se genera cuando termina la investigacion.'
                : 'Esta investigacion no llego a generar informe.'}
            </p>
          )}
        </section>

        <section className="panel">
          <h2>Progreso</h2>
          <ListaEventos eventos={eventos} conectado={conectado} />
        </section>
      </main>
    </div>
  );
}
