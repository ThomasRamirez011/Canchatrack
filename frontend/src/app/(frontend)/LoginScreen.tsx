"use client";

import { useState, type FormEvent } from "react";
import { ApiError, DEFAULT_API_URL, getApiUrl, login, setApiUrl, type Session } from "@/lib/api";

export default function LoginScreen({ onLogin, notice }: { onLogin: (session: Session) => void; notice?: string }) {
  const [email, setEmail] = useState("coordinador@canchatrack.local");
  const [password, setPassword] = useState("");
  const [apiUrl, setApiUrlField] = useState(() => getApiUrl());
  const [showServer, setShowServer] = useState(false);
  const [error, setError] = useState(notice ?? "");
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError("");
    setBusy(true);
    try {
      setApiUrl(apiUrl);
      onLogin(await login(email, password));
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : "No fue posible iniciar sesión. Inténtalo de nuevo.");
      setBusy(false);
    }
  };

  const field = "min-h-11 rounded-lg border border-slate-200 bg-white px-3 text-sm text-slate-900 outline-none focus:border-blue-600 focus:ring-4 focus:ring-blue-100";

  return (
    <main className="grid min-h-screen place-items-center bg-slate-100 px-4 py-10">
      <form onSubmit={submit} className="w-full max-w-sm rounded-2xl bg-white p-8 shadow-xl shadow-slate-900/5">
        <div className="mb-6 text-2xl font-bold text-slate-900">
          Cancha<span className="text-blue-600">Track</span>
        </div>
        <h1 className="text-lg font-semibold text-slate-900">Inicia sesión</h1>
        <p className="mb-6 mt-1 text-sm text-slate-500">Ingresa con tu cuenta del club para ver el tablero.</p>

        <label className="mb-4 flex flex-col gap-1.5 text-xs font-semibold text-slate-600">
          Correo
          <input className={field} type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label className="mb-4 flex flex-col gap-1.5 text-xs font-semibold text-slate-600">
          Contraseña
          <input className={field} type="password" autoComplete="current-password" required autoFocus value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>

        <button type="button" className="mb-3 text-xs font-semibold text-blue-600" onClick={() => setShowServer(!showServer)}>
          {showServer ? "Ocultar servidor" : "Cambiar servidor"}
        </button>
        {showServer && (
          <label className="mb-4 flex flex-col gap-1.5 text-xs font-semibold text-slate-600">
            URL del backend
            <input className={field} type="url" required value={apiUrl} onChange={(e) => setApiUrlField(e.target.value)} placeholder={DEFAULT_API_URL} />
            <span className="font-normal text-slate-400">Por defecto el backend corre en tu equipo. Usa Chrome o Edge.</span>
          </label>
        )}

        {error && (
          <div role="alert" className="mb-4 rounded-lg bg-orange-50 px-3 py-2 text-xs font-medium text-orange-700">
            {error}
          </div>
        )}
        <button type="submit" disabled={busy} className="min-h-11 w-full rounded-lg bg-blue-600 text-sm font-bold text-white transition hover:bg-blue-700 disabled:opacity-60">
          {busy ? "Entrando…" : "Entrar"}
        </button>
      </form>
    </main>
  );
}
