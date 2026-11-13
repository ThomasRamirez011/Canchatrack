# CanchaTrack · front estático

Copia autónoma de las pantallas del prototipo (`ClubApp`, `CanchaTrackApp`, `LoginScreen`) para publicarlas
como sitio estático en GitHub Pages. No usa Payload ni Figma Make: todo se ejecuta en el navegador y llama
al backend de Spring Boot (`/api/v1`).

- **Local**: `pnpm install && pnpm dev` → http://localhost:3000 (backend por defecto en `http://localhost:8080/api/v1`).
- **Publicado**: lo construye `.github/workflows/frontend-pages.yml` con `BASE_PATH=/canchatrack` y
  `NEXT_PUBLIC_API_URL` apuntando al backend de Render.
- **Duplicación consciente**: estos archivos se copiaron de `src/` (raíz). Si se cambian las pantallas, hay que
  actualizar ambas carpetas hasta que el prototipo de Figma Make se retire.
- El sitio publicado exige que su origen (`https://thomasramirez011.github.io`) esté en `CORS_ALLOWED_ORIGINS` del backend.
