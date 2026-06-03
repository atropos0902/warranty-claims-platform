import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

const claims = process.env['CLAIMS_URL'] ?? 'http://localhost:8081';
const pricing = process.env['PRICING_URL'] ?? 'http://localhost:8082';
const assistant = process.env['ASSISTANT_URL'] ?? 'http://localhost:8083';
const proxy = {
  '/api/v1/pricing': pricing,
  '/api/v1/assistant': assistant,
  '/api/v1': claims,
  '/dev/token': claims,
};

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy },
  preview: { port: 4173, proxy },
  test: { environment: 'jsdom', include: ['src/**/*.test.{ts,tsx}'] },
});
