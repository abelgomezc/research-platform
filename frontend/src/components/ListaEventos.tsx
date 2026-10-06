import type { ResearchEvent } from '../types';

interface Props {
  eventos: ResearchEvent[];
  conectado?: boolean;
}

/**
 * Traduce el payload de cada evento a algo legible.
 *
 * <p>El backend manda el dato crudo porque la interfaz no debe depender del
 * formato. Aqui se interpreta, que es donde debe estar: si el payload cambia, se
 * toca un unico sitio.
 */
function describir(evento: ResearchEvent): string {
  const p = evento.payload ?? {};
  const numero = (clave: string): string => {
    const valor = p[clave];
    return valor === undefined || valor === null ? '' : String(valor);
  };

  switch (evento.tipo) {
    case 'INVESTIGATION_CREATED':
      return `Investigacion creada con presupuesto de ${numero('presupuestoTokens')} tokens`;
    case 'STATE_CHANGED':
      return `Estado: ${numero('desde')} -> ${numero('hacia')}`;
    case 'PLAN_CREATED':
      return `Plan creado en la ronda ${numero('ronda')}`;
    case 'ROUND_STARTED':
      return `Empieza la ronda ${numero('ronda')}`;
    case 'TASK_STARTED':
      return `Tarea ${numero('tareaId')} iniciada`;
    case 'TASK_COMPLETED':
      return `Tarea ${numero('tareaId')} completada`;
    case 'TASK_DISCARDED':
      return `Tarea ${numero('tareaId')} descartada`;
    case 'TASK_FAILED':
      return `Tarea ${numero('tareaId')} con problemas: ${numero('detalle')}`;
    case 'EVIDENCE_SAVED':
      return `Evidencia ${numero('evidenciaId')} registrada, pendiente de verificar`;
    case 'EVIDENCE_VERIFIED': {
      const estado = numero('estado');
      const modo = numero('modoCoincidencia');
      return `Evidencia ${numero('evidenciaId')}: ${estado}${modo ? ` (${modo})` : ''}`;
    }
    case 'CONTRADICTION_DETECTED':
      return `Contradiccion entre las evidencias ${numero('evidenciaA')} y ${numero('evidenciaB')}`;
    case 'REPORT_GENERATED':
      return `Informe generado, validacion ${numero('aprobado') === 'true' ? 'correcta' : 'con problemas'}`;
    case 'REVIEW_COMPLETED':
      return `Revision ${numero('aprobado') === 'true' ? 'aprobada' : 'rechazada'}`;
    case 'BUDGET_LOW':
      return `Aviso de presupuesto: ${numero('porcentaje')}% consumido`;
    case 'ROUNDS_EXHAUSTED':
      return 'Se agotaron las rondas disponibles';
    case 'CHECKPOINT_SAVED':
      return `Checkpoint guardado en la ronda ${numero('ronda')}`;
    case 'CHECKPOINT_RESUMED':
      return `Reanudacion: ${numero('tareasDevueltas')} tareas devueltas a la cola`;
    case 'CANCELLATION_REQUESTED':
      return 'Cancelacion solicitada';
    case 'FAILURE':
      return `Fallo: ${numero('motivo')}`;
    default:
      return evento.tipo;
  }
}

/** Eventos que son ruido visual y no se muestran. */
const RUIDO = new Set(['EVIDENCE_SAVED']);

export default function ListaEventos({ eventos, conectado = true }: Props) {
  const visibles = [...eventos].reverse().filter((e) => !RUIDO.has(e.tipo));

  if (visibles.length === 0) {
    return (
      <p className="vacio">
        {conectado ? 'Conectando al stream...' : 'Sin eventos todavia.'}
      </p>
    );
  }

  return (
    <ul className="eventos">
      {visibles.map((evento) => (
        <li key={`${evento.id}-${evento.tipo}`} className="evento">
          <span className={`evento-tipo evento-${evento.tipo.toLowerCase()}`}>
            {evento.tipo}
          </span>
          <span className="evento-descripcion">{describir(evento)}</span>
        </li>
      ))}
    </ul>
  );
}
