// Cliente del backend de CanchaTrack (Spring Boot). Se ejecuta en el navegador: el backend corre en el equipo de quien
// usa el prototipo (por defecto http://localhost:8080/api/v1), así que la llamada sale desde el navegador, no desde el servidor.

export const DEFAULT_API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080/api/v1";
export const CLUB_TIME_ZONE = "America/Bogota";

const URL_KEY = "canchatrack.apiUrl";
const SESSION_KEY = "canchatrack.session";

// ---------- tipos del backend (ver openapi/canchatrack-v1.yaml) ----------

export type ApiUser = { id: string; email: string; fullName: string; roles: string[] };

export type Session = {
  accessToken: string;
  refreshToken: string;
  expiresAt: number; // ms desde epoch
  user: ApiUser;
};

export type ApiTeam = {
  categoryId: string;
  name: string;
  headCoachId: string | null;
  headCoach: string | null;
  rosterSize: number;
  enabledCount: number;
  attendanceAverage: number;
  recentAttendance: { eventId: string; startAt: string; percentage: number }[];
  monthRevenue: number;
  debt: number;
  collectionRate: number;
  nextMatch: { eventId: string; startAt: string; opponent: string; venue: string } | null;
};

export type ApiPendingCharge = {
  chargeId: string;
  concept: string;
  feeName: string;
  period: string;
  dueDate: string;
  outstanding: number;
};

export type ApiPlayer = {
  playerId: string;
  categoryId: string;
  code: string;
  fullName: string;
  status: string;
  attendance: number;
  enabled: boolean;
  pendingCharge: ApiPendingCharge | null;
};

export type ApiEvent = {
  id: string;
  categoryId: string;
  eventType: "TRAINING" | "EVALUATION" | "FRIENDLY_MATCH" | "TOURNAMENT_MATCH";
  status: "CONFIRMED" | "CANCELLED" | "COMPLETED";
  startAt: string;
  endAt: string;
  venue: string;
  opponent: string | null;
};

export type ClubOverview = { generatedAt: string; teams: ApiTeam[]; players: ApiPlayer[]; events: ApiEvent[] };
export type Venue = { id: string; name: string; active: boolean };

export class ApiError extends Error {
  status: number;
  fieldErrors: Record<string, string>;
  constructor(message: string, status: number, fieldErrors: Record<string, string> = {}) {
    super(message);
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}

// ---------- almacenamiento (solo navegador) ----------

function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string | null) {
  try {
    if (value === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, value);
  } catch {
    /* sin almacenamiento: la sesión vive solo en memoria */
  }
}

export function normalizeApiUrl(raw: string): string {
  const trimmed = raw.trim().replace(/\/+$/, "");
  return trimmed || DEFAULT_API_URL;
}

export function getApiUrl(): string {
  return normalizeApiUrl(read(URL_KEY) ?? DEFAULT_API_URL);
}

export function setApiUrl(url: string) {
  write(URL_KEY, normalizeApiUrl(url));
}

export function loadSession(): Session | null {
  const raw = read(SESSION_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Session;
    return parsed.accessToken && parsed.refreshToken && parsed.user ? parsed : null;
  } catch {
    return null;
  }
}

function saveSession(session: Session | null) {
  write(SESSION_KEY, session ? JSON.stringify(session) : null);
}

export function logout() {
  const session = loadSession();
  saveSession(null);
  if (session) {
    // Mejor esfuerzo: revoca el refresh token; si el servidor no responde, la sesión local ya se cerró.
    void fetch(`${getApiUrl()}/auth/logout`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken: session.refreshToken }),
    }).catch(() => undefined);
  }
}

// ---------- peticiones ----------

type SessionBody = {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  user: ApiUser;
};

function toSession(body: SessionBody): Session {
  return {
    accessToken: body.accessToken,
    refreshToken: body.refreshToken,
    expiresAt: Date.now() + body.expiresIn * 1000,
    user: body.user,
  };
}

async function parseError(response: Response): Promise<ApiError> {
  let detail = "";
  const fields: Record<string, string> = {};
  try {
    const problem = await response.json();
    detail = typeof problem?.detail === "string" ? problem.detail : "";
    if (Array.isArray(problem?.errors)) {
      for (const item of problem.errors) {
        if (item?.field && item?.message) fields[String(item.field)] = String(item.message);
      }
    }
  } catch {
    /* cuerpo no JSON */
  }
  const fallback: Record<number, string> = {
    401: "Credenciales inválidas o sesión vencida.",
    403: "Tu cuenta no tiene permiso para esta acción.",
    404: "No se encontró lo que buscabas.",
    409: "La acción choca con algo que ya existe (por ejemplo, un cruce de agenda).",
    422: "Revisa los datos enviados.",
    429: "Demasiados intentos. Espera un momento y vuelve a intentar.",
  };
  return new ApiError(detail || fallback[response.status] || `El servidor respondió ${response.status}.`, response.status, fields);
}

async function send(path: string, init: RequestInit, token?: string): Promise<Response> {
  const headers = new Headers(init.headers);
  if (init.body) headers.set("Content-Type", "application/json");
  if (!headers.has("Accept")) headers.set("Accept", "application/json");
  if (token) headers.set("Authorization", `Bearer ${token}`);
  try {
    return await fetch(`${getApiUrl()}${path}`, { ...init, headers });
  } catch {
    throw new ApiError(
      `No se pudo conectar con el servidor en ${getApiUrl()}. Verifica que el backend esté encendido y que uses Chrome o Edge.`,
      0,
    );
  }
}

export async function login(email: string, password: string): Promise<Session> {
  const response = await send("/auth/login", { method: "POST", body: JSON.stringify({ email: email.trim(), password }) });
  if (response.status === 202) {
    throw new ApiError("Esta cuenta exige segundo factor (MFA), que el prototipo no incluye. Usa una cuenta sin MFA.", 202);
  }
  if (!response.ok) throw await parseError(response);
  const session = toSession((await response.json()) as SessionBody);
  saveSession(session);
  return session;
}

let refreshing: Promise<Session | null> | null = null;

async function refresh(): Promise<Session | null> {
  const current = loadSession();
  if (!current) return null;
  // Un refresh token se usa una sola vez: las llamadas simultáneas comparten la misma renovación.
  refreshing ??= (async () => {
    const response = await send("/auth/refresh", { method: "POST", body: JSON.stringify({ refreshToken: current.refreshToken }) });
    if (!response.ok) {
      saveSession(null);
      return null;
    }
    const next = toSession((await response.json()) as SessionBody);
    saveSession(next);
    return next;
  })().finally(() => {
    refreshing = null;
  });
  return refreshing;
}

async function authedResponse(path: string, init: RequestInit = {}): Promise<Response> {
  let session = loadSession();
  if (!session) throw new ApiError("Inicia sesión para continuar.", 401);
  if (session.expiresAt - Date.now() < 15_000) {
    session = (await refresh()) ?? null;
    if (!session) throw new ApiError("Tu sesión venció. Inicia sesión de nuevo.", 401);
  }
  let response = await send(path, init, session.accessToken);
  if (response.status === 401) {
    const renewed = await refresh();
    if (!renewed) throw new ApiError("Tu sesión venció. Inicia sesión de nuevo.", 401);
    response = await send(path, init, renewed.accessToken);
  }
  if (!response.ok) throw await parseError(response);
  return response;
}

async function authed<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await authedResponse(path, init);
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

export function fetchOverview(): Promise<ClubOverview> {
  return authed<ClubOverview>("/dashboard/club");
}

export function fetchVenues(): Promise<Venue[]> {
  return authed<Venue[]>("/venues");
}

export type ScheduleEventInput = {
  eventType: "TRAINING" | "EVALUATION";
  categoryId: string;
  venueId: string;
  headCoachId: string;
  startAt: string; // ISO-8601 con zona
  endAt: string;
};

export function scheduleEvent(input: ScheduleEventInput): Promise<unknown> {
  return authed("/events", { method: "POST", body: JSON.stringify(input) });
}

export function recordPayment(playerId: string, chargeId: string, amount: number): Promise<unknown> {
  return authed(`/players/${playerId}/payments`, {
    method: "POST",
    body: JSON.stringify({ chargeId, amount, method: "TRANSFER" }),
  });
}

// ---------- jugadores: deshabilitar y rehabilitar ----------

export type PlayerStatusChange = { playerId: string; previousStatus: string; status: string };

export function suspendPlayer(playerId: string, reason: string): Promise<PlayerStatusChange> {
  return authed(`/players/${playerId}/suspension`, { method: "POST", body: JSON.stringify({ reason }) });
}

export function reinstatePlayer(playerId: string, reason?: string): Promise<PlayerStatusChange> {
  return authed(`/players/${playerId}/reinstatement`, { method: "POST", body: JSON.stringify({ reason: reason || null }) });
}

// ---------- convocatoria del próximo partido ----------

export type CallupStatus = "PROPOSED" | "CONFIRMED" | "NOT_ELIGIBLE" | "REMOVED";
export type IneligibilityCause = "PLAYER_NOT_ACTIVE" | "MEDICAL_CERTIFICATE_INVALID" | "PARTICIPATION_AUTHORIZATION_INVALID" | "ARREARS_POLICY";

export type Callup = {
  playerId: string;
  playerCode: string;
  firstName: string;
  lastName: string;
  status: CallupStatus;
  ineligibilityCode: IneligibilityCause | null;
};

export type MatchDetail = {
  match: { id: string; eventId: string; opponent: string; startAt: string; venueName: string; status: string };
  callups: Callup[];
};

/** El tablero conoce el evento del partido; la convocatoria cuelga del partido, así que se busca por su evento. */
export async function fetchMatchByEvent(categoryId: string, eventId: string): Promise<MatchDetail | null> {
  const matches = await authed<{ id: string; eventId: string }[]>(`/matches?categoryId=${encodeURIComponent(categoryId)}`);
  const found = matches.find((match) => match.eventId === eventId);
  return found ? authed<MatchDetail>(`/matches/${found.id}`) : null;
}

export function proposeCallup(matchId: string, playerIds: string[]): Promise<unknown> {
  return authed(`/matches/${matchId}/callup`, { method: "POST", body: JSON.stringify({ playerIds }) });
}

export function confirmCallup(matchId: string): Promise<unknown> {
  return authed(`/matches/${matchId}/callup/confirm`, { method: "POST" });
}

// ---------- reportes descargables (CSV, abre en Excel) ----------

export async function downloadCsv(path: string, fileName: string): Promise<void> {
  const response = await authedResponse(path, { headers: { Accept: "text/csv" } });
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
