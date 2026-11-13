import type { NextConfig } from 'next'

// Publicación estática (GitHub Pages sirve el sitio bajo /Canchatrack (el nombre del repo, con mayúsculas: la ruta distingue mayúsculas)).
// BASE_PATH queda vacío en local para que `pnpm dev` siga en http://localhost:3000.
const basePath = process.env.BASE_PATH ?? ''

const nextConfig: NextConfig = {
  output: 'export',
  basePath,
  trailingSlash: true,
  images: { unoptimized: true },
  env: { NEXT_PUBLIC_BASE_PATH: basePath },
}

export default nextConfig
