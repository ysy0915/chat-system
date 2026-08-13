Vite frontend for chat-system-project

Development:
  cd frontend
  npm install
  npm run dev

Build (produce static files under src/main/resources/static/chat):
  cd frontend
  npm run build

The backend forwards /chat to /chat/index.html so after building, the SPA is served by Spring Boot.
