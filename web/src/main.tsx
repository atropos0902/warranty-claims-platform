import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { Problem } from './api/client';
import { App } from './App';
import './ui/styles.css';

const client = new QueryClient({
  defaultOptions: {
    queries: { retry: (n, e) => n < 2 && !(e instanceof Problem && e.status < 500), staleTime: 5_000 },
  },
});

createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={client}>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </QueryClientProvider>,
);
