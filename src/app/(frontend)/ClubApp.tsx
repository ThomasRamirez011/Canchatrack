"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ApiError,
  confirmCallup,
  downloadCsv,
  fetchMatchByEvent,
  fetchOverview,
  fetchVenues,
  loadSession,
  logout,
  proposeCallup,
  recordPayment,
  reinstatePlayer,
  scheduleEvent,
  suspendPlayer,
  type ClubOverview,
  type Session,
  type Venue,
} from "@/lib/api";
import { formatMoney, mapOverview } from "@/lib/map-overview";
import CanchaTrackApp, { type CallupLoad, type NewEventInput, type PlayerData, type ReportKind, type TeamData } from "./CanchaTrackApp";
import LoginScreen from "./LoginScreen";

type Phase = "starting" | "login" | "loading" | "ready" | "failed";

const CLUB_OFFSET = "-05:00"; // Colombia no usa horario de verano.

export default function ClubApp() {
  const [phase, setPhase] = useState<Phase>("starting");
  const [session, setSession] = useState<Session | null>(null);
  const [overview, setOverview] = useState<ClubOverview | null>(null);
  const [venues, setVenues] = useState<Venue[]>([]);
  const [message, setMessage] = useState("");

  const signOut = useCallback((notice = "") => {
    logout();
    setSession(null);
    setOverview(null);
    setMessage(notice);
    setPhase("login");
  }, []);

  const load = useCallback(async () => {
    try {
      const [data, places] = await Promise.all([fetchOverview(), fetchVenues().catch(() => [] as Venue[])]);
      setOverview(data);
      setVenues(places.filter((venue) => venue.active));
      setPhase("ready");
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        signOut("Tu sesión venció. Inicia sesión de nuevo.");
      } else {
        setMessage(failure instanceof Error ? failure.message : "No fue posible cargar el tablero.");
        setPhase("failed");
      }
    }
  }, [signOut]);

  useEffect(() => {
    const stored = loadSession();
    if (stored) {
      setSession(stored);
      setPhase("loading");
      void load();
    } else {
      setPhase("login");
    }
  }, [load]);

  const data = useMemo(() => (overview ? mapOverview(overview) : null), [overview]);

  const handleLogin = (next: Session) => {
    setSession(next);
    setMessage("");
    setPhase("loading");
    void load();
  };

  const handleCreateEvent = async (input: NewEventInput): Promise<{ ok: true; message: string } | { ok: false; error: string }> => {
    const team = data?.teams.find((item) => item.id === input.categoryId);
    if (!team?.coachId) return { ok: false, error: "Esta categoría aún no tiene entrenador asignado." };
    try {
      await scheduleEvent({
        eventType: input.eventType,
        categoryId: input.categoryId,
        venueId: input.venueId,
        headCoachId: team.coachId,
        startAt: `${input.date}T${input.time}:00${CLUB_OFFSET}`,
        endAt: `${input.date}T${input.endTime}:00${CLUB_OFFSET}`,
      });
      await load();
      return { ok: true, message: "El evento fue agregado al calendario." };
    } catch (failure) {
      return { ok: false, error: failure instanceof Error ? failure.message : "No fue posible crear el evento." };
    }
  };

  const handleRecordPayment = async (playerId: string, chargeId: string, amount: number, playerName: string) => {
    try {
      await recordPayment(playerId, chargeId, amount);
      await load();
      return { ok: true as const, message: `Pago de ${playerName} por ${formatMoney(amount)} registrado.` };
    } catch (failure) {
      return { ok: false as const, error: failure instanceof Error ? failure.message : "No fue posible registrar el pago." };
    }
  };

  const fail = (failure: unknown, fallback: string) => ({ ok: false as const, error: failure instanceof Error ? failure.message : fallback });

  const handleSetPlayerStatus = async (player: PlayerData, mode: "suspend" | "reinstate", reason: string) => {
    try {
      if (mode === "suspend") await suspendPlayer(player.id, reason);
      else await reinstatePlayer(player.id, reason || undefined);
      await load();
      return { ok: true as const, message: mode === "suspend" ? `${player.name} fue deshabilitado.` : `${player.name} fue rehabilitado.` };
    } catch (failure) {
      return fail(failure, "No fue posible cambiar el estado del jugador.");
    }
  };

  const handleLoadCallup = async (categoryId: string, eventId: string): Promise<CallupLoad> => {
    try {
      return { ok: true, detail: await fetchMatchByEvent(categoryId, eventId) };
    } catch (failure) {
      return fail(failure, "No fue posible cargar la convocatoria.");
    }
  };

  const handleProposeCallup = async (matchId: string, playerIds: string[]) => {
    try {
      await proposeCallup(matchId, playerIds);
      return { ok: true as const, message: "Jugadores propuestos para la convocatoria." };
    } catch (failure) {
      return fail(failure, "No fue posible proponer la convocatoria.");
    }
  };

  const handleConfirmCallup = async (matchId: string) => {
    try {
      await confirmCallup(matchId);
      return { ok: true as const, message: "Convocatoria confirmada." };
    } catch (failure) {
      return fail(failure, "No fue posible confirmar la convocatoria.");
    }
  };

  const handleDownloadReport = async (kind: ReportKind, team: TeamData) => {
    const pad = (value: number) => String(value).padStart(2, "0");
    const iso = (date: Date) => `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
    const today = new Date();
    const from = new Date(today);
    from.setDate(from.getDate() - 120);
    const query = `categoryId=${encodeURIComponent(team.id)}`;
    const path = kind === "attendance"
      ? `/reports/attendance/export?${query}&from=${iso(from)}&to=${iso(today)}`
      : `/reports/${kind}/export?${query}`;
    const slug = team.label.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
    try {
      await downloadCsv(path, `${kind}-${slug}-${iso(today)}.csv`);
      return { ok: true as const, message: "Reporte descargado." };
    } catch (failure) {
      return fail(failure, "No fue posible descargar el reporte.");
    }
  };

  if (phase === "starting" || phase === "loading") {
    return <main className="grid min-h-screen place-items-center bg-slate-100 text-sm text-slate-500">Cargando el tablero…</main>;
  }
  if (phase === "login" || !session) {
    return <LoginScreen onLogin={handleLogin} notice={message} />;
  }
  if (phase === "failed" || !data) {
    return (
      <main className="grid min-h-screen place-items-center bg-slate-100 px-4">
        <div className="max-w-md rounded-2xl bg-white p-8 text-center shadow-xl shadow-slate-900/5">
          <strong className="block text-slate-900">No se pudo cargar el tablero</strong>
          <p className="my-3 text-sm text-slate-500">{message}</p>
          <div className="flex justify-center gap-3">
            <button type="button" className="min-h-10 rounded-lg bg-blue-600 px-4 text-sm font-bold text-white" onClick={() => { setPhase("loading"); void load(); }}>Reintentar</button>
            <button type="button" className="min-h-10 rounded-lg bg-slate-100 px-4 text-sm font-bold text-slate-700" onClick={() => signOut()}>Cambiar servidor</button>
          </div>
        </div>
      </main>
    );
  }

  return (
    <CanchaTrackApp
      teams={data.teams}
      players={data.players}
      events={data.events}
      venues={venues}
      user={session.user}
      onLogout={() => signOut()}
      onCreateEvent={handleCreateEvent}
      onRecordPayment={handleRecordPayment}
      onSetPlayerStatus={handleSetPlayerStatus}
      onLoadCallup={handleLoadCallup}
      onProposeCallup={handleProposeCallup}
      onConfirmCallup={handleConfirmCallup}
      onDownloadReport={handleDownloadReport}
    />
  );
}
