import type {
  CancelResponse,
  Checkpoint,
  CreateResearchRequest,
  ReportRow,
  ResearchDetail,
  ResearchEvent,
  ResearchListItem,
} from './types';

/**
 * Cliente de la API del backend.
 *
 * <p>Las llamadas van a rutas relativas y no a un host absoluto: el navegador las
 * resuelve contra el origen actual, y en desarrollo y en produccion el proxy las
 * lleva al backend. Una URL absoluta obligaria a recompilar el frontend al
 * cambiar el despliegue.
 */
const BASE = '/api';

async function pedir<T>(ruta: string, opciones?: RequestInit): Promise<T> {
  const respuesta = await fetch(`${BASE}${ruta}`, {
    ...opciones,
    headers: {
      'Content-Type': 'application/json',
      ...(opciones?.headers ?? {}),
    },
  });

  if (!respuesta.ok) {
    // Se intenta leer el cuerpo del error: el backend devuelve ApiErrorResponse
    // con un mensaje en espanol que es mucho mas util que "500".
    let mensaje = `Error ${respuesta.status}`;
    try {
      const cuerpo = await respuesta.json();
      if (cuerpo?.message) {
        mensaje = cuerpo.message;
      } else if (cuerpo?.mensaje) {
        mensaje = cuerpo.mensaje;
      }
    } catch {
      // El cuerpo no era JSON: se queda con el mensaje por defecto.
    }
    throw new Error(mensaje);
  }

  if (respuesta.status === 204) {
    return undefined as T;
  }
  return (await respuesta.json()) as T;
}

export const api = {
  listarInvestigaciones: (limite = 20, desplazamiento = 0): Promise<ResearchListItem[]> =>
    pedir(`/research?limite=${limite}&desplazamiento=${desplazamiento}`),

  obtenerInvestigacion: (id: number): Promise<ResearchDetail> => pedir(`/research/${id}`),

  crearInvestigacion: (datos: CreateResearchRequest): Promise<{ id: number; mensaje: string }> =>
    pedir('/research', {
      method: 'POST',
      body: JSON.stringify(datos),
    }),

  cancelar: (id: number): Promise<CancelResponse> =>
    pedir(`/research/${id}/cancel`, { method: 'POST' }),

  reanudar: (id: number): Promise<{ reanudada: boolean; mensaje: string }> =>
    pedir(`/research/${id}/resume`, { method: 'POST' }),

  checkpoint: (id: number): Promise<Checkpoint> =>
    pedir(`/research/${id}/checkpoint`, { method: 'POST' }),

  informe: (id: number, historico = false): Promise<ReportRow | ReportRow[]> =>
    pedir(`/research/${id}/report?historico=${historico}`),

  eventos: (id: number, desdeId = 0): Promise<ResearchEvent[]> =>
    pedir(`/research/${id}/events?desdeId=${desdeId}&limite=200`),

  listarDocumentos: () => pedir<{ documentos: unknown[] }>('/documents'),

  estadoConocimiento: () => pedir<Record<string, unknown>>('/knowledge/status'),
};

/**
 * Abre el stream de eventos de una investigacion.
 *
 * <p>Se usa {@code EventSource} y no un fetch con streaming porque el navegador
 * reconecta solo y reenvia {@code Last-Event-ID}. Esa reconexion automatica es
 * justamente lo que hace que un corte de red no pierda el progreso: el backend
 * guarda los eventos y los reenvia desde el ultimo recibido.
 */
export function abrirStream(
  id: number,
  alRecibir: (evento: ResearchEvent) => void,
  alError?: () => void,
): EventSource {
  const fuente = new EventSource(`${BASE}/research/${id}/stream`);

  const TIPOS = [
    'INVESTIGATION_CREATED',
    'STATE_CHANGED',
    'PLAN_CREATED',
    'TASK_STARTED',
    'TASK_COMPLETED',
    'TASK_DISCARDED',
    'TASK_FAILED',
    'EVIDENCE_SAVED',
    'EVIDENCE_VERIFIED',
    'CONTRADICTION_DETECTED',
    'REPORT_GENERATED',
    'REVIEW_COMPLETED',
    'BUDGET_LOW',
    'ROUND_STARTED',
    'ROUNDS_EXHAUSTED',
    'CHECKPOINT_SAVED',
    'CHECKPOINT_RESUMED',
    'CANCELLATION_REQUESTED',
    'FAILURE',
  ];

  for (const tipo of TIPOS) {
    fuente.addEventListener(tipo, (evento) => {
      try {
        const payload = JSON.parse((evento as MessageEvent).data);
        const idEvento = (evento as MessageEvent).lastEventId;
        alRecibir({
          id: idEvento ? Number(idEvento) : Date.now(),
          tipo,
          payload,
          creadoEn: new Date().toISOString(),
        });
      } catch {
        // Un payload ilegible no debe romper el stream: se ignora ese evento
        // y se sigue leyendo los siguientes.
      }
    });
  }

  fuente.addEventListener('estado', (evento) => {
    try {
      const payload = JSON.parse((evento as MessageEvent).data);
      alRecibir({
        id: 0,
        tipo: 'ESTADO',
        payload,
        creadoEn: new Date().toISOString(),
      });
    } catch {
      // Igual que arriba: el cierre del stream no es critico.
    }
  });

  if (alError) {
    fuente.onerror = () => alError();
  }

  return fuente;
}
