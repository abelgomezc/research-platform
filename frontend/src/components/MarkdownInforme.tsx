import { useMemo } from 'react';

interface Props {
  contenido: string;
}

/**
 * Render del informe en markdown.
 *
 * <p>Se renderiza a mano en vez de usar una libreria porque el informe lo genera
 * el sintetizador, no el usuario. No hay entrada externa sin verificar: el texto
 * ya paso por {@code ReportValidator}, que exige que cada afirmacion cite
 * evidencia. Una libreria de markdown generalista trataria esto como HTML
 * arbitrario y abriria una puerta que el validador acaba de cerrar.
 *
 * <p>Lo que no se hace: interpretar etiquetas HTML del contenido. Si el informe
 * trae `<script>`, se muestra como texto.
 */
export default function MarkdownInforme({ contenido }: Props) {
  const bloques = useMemo(() => convertir(contenido), [contenido]);

  return (
    <article className="informe">
      {bloques.map((bloque, indice) => {
        switch (bloque.tipo) {
          case 'h1':
            return <h2 key={indice}>{bloque.texto}</h2>;
          case 'h2':
            return <h3 key={indice}>{bloque.texto}</h3>;
          case 'h3':
            return <h4 key={indice}>{bloque.texto}</h4>;
          case 'li':
            return (
              <li key={indice} className="informe-item">
                {resaltarCitas(bloque.texto)}
              </li>
            );
          case 'p':
            return <p key={indice}>{resaltarCitas(bloque.texto)}</p>;
          default:
            return null;
        }
      })}
    </article>
  );
}

type Bloque =
  | { tipo: 'h1' | 'h2' | 'h3' | 'p' | 'li'; texto: string };

/**
 * Convierte markdown simple a bloques.
 *
 * <p>Solo el subconjunto que el sintetizador produce. Anadir mas sintaxis sin
 * necesidad seria permitir mas superficie de la que hace falta.
 */
function convertir(markdown: string): Bloque[] {
  const bloques: Bloque[] = [];
  let enLista = false;

  for (const lineaBruta of markdown.split('\n')) {
    const linea = lineaBruta.trim();

    if (linea === '') {
      enLista = false;
      continue;
    }

    if (linea.startsWith('# ')) {
      bloques.push({ tipo: 'h1', texto: linea.slice(2) });
    } else if (linea.startsWith('## ')) {
      bloques.push({ tipo: 'h2', texto: linea.slice(3) });
    } else if (linea.startsWith('### ')) {
      bloques.push({ tipo: 'h3', texto: linea.slice(4) });
    } else if (linea.startsWith('- ') || linea.startsWith('* ')) {
      bloques.push({ tipo: 'li', texto: linea.slice(2) });
      enLista = true;
    } else if (linea.startsWith('|')) {
      // Tablas: se saltan. El sintetizador no las genera y una tabla
      // renderizada a medias se veria peor que omitida.
      continue;
    } else {
      if (enLista) {
        enLista = false;
      }
      bloques.push({ tipo: 'p', texto: linea });
    }
  }

  return bloques;
}

/**
 * Resalta las citas [evidencia N] y enlaza con la seccion de evidencias.
 */
function resaltarCitas(texto: string) {
  const partes = texto.split(/(\[(?:evidencias?|fuente)\s+\d+(?:\s*,\s*\d+)*\])/gi);

  return partes.map((parte, indice) => {
    if (/^\[(?:evidencias?|fuente)\s+\d+/i.test(parte)) {
      const id = parte.replace(/[^0-9, ]/g, '').trim().split(',')[0];
      return (
        <a key={indice} className="cita" href={`#evidencia-${id}`} title={parte}>
          {parte}
        </a>
      );
    }
    return <span key={indice}>{parte}</span>;
  });
}
