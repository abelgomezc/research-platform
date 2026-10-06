import { useEffect, useState } from 'react';
import { api } from './api';
import type { ResearchListItem } from './types';
import { estaActiva } from './types';
import DetalleInvestigacion from './components/DetalleInvestigacion';

export default function App() {
  const [investigaciones, setInvestigaciones] = useState<ResearchListItem[]>([]);
  const [seleccionada, setSeleccionada] = useState<number | null>(null);
  const [objetivo, setObjetivo] = useState('');
  const [presupuesto, setPresupuesto] = useState(200_000);
  const [maxRondas, setMaxRondas] = useState(3);
  const [cargando, setCargando] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void refrescar();
  }, []);

  async function refrescar() {
    try {
      setInvestigaciones(await api.listarInvestigaciones());
    } catch (excepcion) {
      setError(excepcion instanceof Error ? excepcion.message : 'No se pudo cargar la lista');
    }
  }

  async function crear() {
    if (!objetivo.trim()) {
      setError('Escribe un objetivo de investigacion');
      return;
    }

    setCargando(true);
    setError(null);
    try {
      const respuesta = await api.crearInvestigacion({
        objetivo: objetivo.trim(),
        presupuestoTokens: presupuesto,
        maxRondas,
      });
      setObjetivo('');
      await refrescar();
      setSeleccionada(respuesta.id);
    } catch (excepcion) {
      setError(excepcion instanceof Error ? excepcion.message : 'No se pudo crear la investigacion');
    } finally {
      setCargando(false);
    }
  }

  if (seleccionada !== null) {
    return (
      <DetalleInvestigacion
        id={seleccionada}
        onVolver={() => {
          setSeleccionada(null);
          void refrescar();
        }}
      />
    );
  }

  return (
    <div className="app">
      <header className="cabecera">
        <h1>Plataforma de investigacion</h1>
        <p className="subtitulo">
          Cada afirmacion del informe cita su evidencia verificada.
        </p>
      </header>

      <main className="contenido">
        <section className="panel">
          <h2>Nueva investigacion</h2>

          <textarea
            className="campo campo-texto"
            placeholder="Pregunta de investigacion. Por ejemplo: que impacto tienen los modelos de deteccion de fraude en el tiempo de respuesta en pagos online"
            value={objetivo}
            onChange={(e) => setObjetivo(e.target.value)}
            rows={4}
            maxLength={2000}
          />

          <div className="fila-campos">
            <label>
              Presupuesto (tokens)
              <input
                className="campo"
                type="number"
                value={presupuesto}
                min={10000}
                max={2000000}
                step={10000}
                onChange={(e) => setPresupuesto(Number(e.target.value))}
              />
            </label>

            <label>
              Rondas maximas
              <input
                className="campo"
                type="number"
                value={maxRondas}
                min={1}
                max={5}
                onChange={(e) => setMaxRondas(Number(e.target.value))}
              />
            </label>
          </div>

          <button className="boton boton-primario" onClick={crear} disabled={cargando}>
            {cargando && <span className="spinner" />}
            {cargando ? 'Creando...' : 'Iniciar investigacion'}
          </button>

          {error && <p className="error">{error}</p>}
        </section>

        <section className="panel">
          <h2>Investigaciones</h2>

          {investigaciones.length === 0 ? (
            <p className="vacio">Todavia no hay investigaciones.</p>
          ) : (
            <ul className="lista">
              {investigaciones.map((investigacion) => (
                <li key={investigacion.id}>
                  <button
                    className="elemento-lista"
                    onClick={() => setSeleccionada(investigacion.id)}
                  >
                    <span className={`estado estado-${investigacion.estado.toLowerCase()}`}>
                      {investigacion.estado}
                    </span>
                    <span className="objetivo">{investigacion.objetivo}</span>
                    <span className="meta">
                      Ronda {investigacion.ronda} |{' '}
                      {investigacion.tokensConsumidos.toLocaleString('es')} /{' '}
                      {investigacion.presupuestoTokens.toLocaleString('es')} tokens
                    </span>
                  </button>
                  {estaActiva(investigacion.estado) && (
                    <button
                      className="boton boton-peligro boton-cancelar-lista"
                      onClick={async (e) => {
                        e.stopPropagation();
                        if (!confirm('¿Estas seguro de cancelar esta investigacion?')) return;
                        try {
                          await api.cancelar(investigacion.id);
                          await refrescar();
                        } catch (excepcion) {
                          setError(excepcion instanceof Error ? excepcion.message : 'No se pudo cancelar');
                        }
                      }}
                      title="Cancelar investigacion"
                    >
                      Cancelar
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </section>
      </main>
    </div>
  );
}
