import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./styles.css";

const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? "";

export const metadata: Metadata = {
  title: "CanchaTrack",
  description: "Gestión de asistencia, pagos y calendario para clubes deportivos formativos.",
  icons: { icon: `${basePath}/favicon.svg` },
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="es">
      <body className="min-h-screen bg-white text-zinc-950 antialiased">
        <div id="root" tabIndex={-1}>
          {children}
        </div>
      </body>
    </html>
  );
}
