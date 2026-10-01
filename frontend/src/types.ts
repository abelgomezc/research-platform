/**
 * Tipos del dominio, alineados con los DTOs del backend.
 *
 * <p>Se replican aqui en vez de generar un cliente automatico porque son pocos y
 * estables. Si alguno se desincroniza, el error aparece al compilar TypeScript, no
 * en ejecucion.
 */

export type ResearchState =
  | 'CREATED'
  | 'PLANNING'
  | 'RESEARCHING'
  | 'VERIFYING'
  | 'SYNTHESIZING'
  | 'REVIEWING'
  | 'COMPLETED'
  | 'INTERRUPTED'
  | 'FAILED'
  | 'CANCELLED';

export interface VerificationSummary {
  total: number;
  verificadas: number;
  noVerificadas: number;
  parciales: number;
  pendientes: number;
}

export interface ResearchListItem {
  id: number;
  objetivo: string;
  estado: ResearchState;
  ronda: number;
  tokensConsumidos: number;
  presupuestoTokens: number;
}

export interface ResearchDetail {
  id: number;
  objetivo: string;
  estado: ResearchState;
  ronda: number;
  maxRondas: number;
  presupuestoTokens: number;
  tokensConsumidos: number;
  motivoFallo: string | null;
  verificacion: VerificationSummary;
  totalTareas: number;
  totalInformes: number;
}

export interface ReportRow {
  id: number;
  version: number;
  contenidoMarkdown: string;
  estadoRevision: 'BORRADOR' | 'EN_REVISION' | 'APROBADO';
  advertencias: string[];
}

export interface ResearchEvent {
  id: number;
  tipo: string;
  payload: Record<string, unknown>;
  creadoEn: string;
}

export interface CreateResearchRequest {
  objetivo: string;
  presupuestoTokens: number;
  maxRondas: number;
}

export interface CancelResponse {
  id: number;
  estado: ResearchState;
  mensaje: string;
}

export interface Checkpoint {
  investigacionId: number;
  estado: ResearchState;
  ronda: number;
  rondasAgotadas: boolean;
  presupuestoAgotado: boolean;
  tareasPendientes: number;
  tareasEnCurso: number;
  verificadas: number;
  totalEvidencias: number;
}

/** Estados en los que la investigacion sigue viva. */
export const ESTADOS_ACTIVOS: ResearchState[] = [
  'CREATED',
  'PLANNING',
  'RESEARCHING',
  'VERIFYING',
  'SYNTHESIZING',
  'REVIEWING',
];

export function estaActiva(estado: ResearchState): boolean {
  return ESTADOS_ACTIVOS.includes(estado);
}
