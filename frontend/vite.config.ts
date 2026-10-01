import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * En desarrollo el frontend corre en 5174 y hace proxy de /api al backend en 8081.
 * El proxy existe para no depender de CORS durante el desarrollo, igual que hace
 * nginx en produccion.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    host: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/actuator': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
});
