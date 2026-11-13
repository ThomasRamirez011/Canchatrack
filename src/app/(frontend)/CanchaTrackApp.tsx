"use client";

import { useState, type FormEvent, type ReactNode } from "react";
import type { ApiUser, Venue } from "@/lib/api";

export type TeamData = {
  id: string;
  label: string;
  shortLabel: string;
  initials: string;
  total: number;
  enabled: number;
  attendanceAverage: number;
  revenue: string;
  debt: string;
  collection: number;
  opponent: string;
  matchTime: string;
  matchVenue: string;
  matchDate: { weekday: string; day: string; month: string } | null;
  coach: string;
  coachId: string | null;
  attendance: { day: string; date: string; value: number }[];
};

export type PlayerData = {
  id: string;
  categoryId: string;
  initials: string;
  name: string;
  number: string;
  status: "Al día" | "Pendiente";
  tone: "blue" | "green" | "orange" | "purple";
  attendance: number;
  enabled: boolean;
  pending: { chargeId: string; amount: number; amountLabel: string; concept: string; dueLabel: string; overdue: boolean } | null;
};

export type NewEventInput = {
  categoryId: string;
  eventType: "TRAINING" | "EVALUATION";
  venueId: string;
  date: string;
  time: string;
  endTime: string;
};

type ActionOutcome = { ok: true; message: string } | { ok: false; error: string };

export type EventData = {
  id: string;
  categoryId: string;
  title: string;
  type: "training" | "match" | "other";
  date: string;
  time: string;
  location: string;
};

type IconName =
  | "grid"
  | "users"
  | "wallet"
  | "calendar"
  | "chart"
  | "settings"
  | "bell"
  | "chevron"
  | "arrow"
  | "check"
  | "clock"
  | "alert"
  | "plus"
  | "menu"
  | "close";

function Icon({ name, className = "size-5" }: { name: IconName; className?: string }) {
  const paths: Record<IconName, ReactNode> = {
    grid: <><rect x="3" y="3" width="7" height="7" rx="2" /><rect x="14" y="3" width="7" height="7" rx="2" /><rect x="3" y="14" width="7" height="7" rx="2" /><rect x="14" y="14" width="7" height="7" rx="2" /></>,
    users: <><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" /></>,
    wallet: <><path d="M20 7V5a2 2 0 0 0-2-2H5a3 3 0 0 0 0 6h15v12H5a3 3 0 0 1-3-3V6" /><path d="M16 13h2" /></>,
    calendar: <><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M16 3v4M8 3v4M3 11h18" /></>,
    chart: <><path d="M3 3v18h18" /><path d="m7 16 4-5 4 3 5-7" /></>,
    settings: <><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 .6 1.7 1.7 0 0 0-.4 1.1V21a2 2 0 1 1-4 0v-.09A1.7 1.7 0 0 0 8.6 19.4a1.7 1.7 0 0 0-1.88.34l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-.6-1 1.7 1.7 0 0 0-1.1-.4H3a2 2 0 1 1 0-4h.09A1.7 1.7 0 0 0 4.6 8.6a1.7 1.7 0 0 0-.34-1.88l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-.6 1.7 1.7 0 0 0 .4-1.1V3a2 2 0 1 1 4 0v.09A1.7 1.7 0 0 0 15.4 4.6a1.7 1.7 0 0 0 1.88-.34l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.7 1.7 0 0 0 19.4 9c.14.36.35.68.6 1 .3.27.68.4 1.1.4h.09a2 2 0 1 1 0 4h-.09A1.7 1.7 0 0 0 19.4 15Z" /></>,
    bell: <><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" /></>,
    chevron: <path d="m9 18 6-6-6-6" />,
    arrow: <path d="m5 12 14 0m-5-5 5 5-5 5" />,
    check: <path d="m5 12 4 4L19 6" />,
    clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
    alert: <><path d="M10.3 3.7 2.7 17a2 2 0 0 0 1.7 3h15.2a2 2 0 0 0 1.7-3L13.7 3.7a2 2 0 0 0-3.4 0Z" /><path d="M12 9v4M12 17h.01" /></>,
    plus: <path d="M12 5v14M5 12h14" />,
    menu: <path d="M4 7h16M4 12h16M4 17h16" />,
    close: <path d="m6 6 12 12M18 6 6 18" />,
  };

  return <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name]}</svg>;
}

function todayLocal() {
  return new Intl.DateTimeFormat("sv-SE", { timeZone: "America/Bogota" }).format(new Date());
}

function roleLabel(roles: string[]) {
  if (roles.includes("COORDINATOR")) return "Coordinador";
  if (roles.includes("COMMITTEE")) return "Comité técnico";
  if (roles.includes("COACH")) return "Entrenador";
  return "Personal del club";
}

const navItems: { label: string; icon: IconName }[] = [
  { label: "Resumen", icon: "grid" },
  { label: "Jugadores", icon: "users" },
  { label: "Pagos", icon: "wallet" },
  { label: "Calendario", icon: "calendar" },
  { label: "Reportes", icon: "chart" },
];

function Button({ children, disabled = false, onClick, variant = "primary", className = "" }: { children: ReactNode; disabled?: boolean; onClick?: () => void; variant?: "primary" | "ghost" | "soft"; className?: string }) {
  return <button type="button" disabled={disabled} onClick={onClick} className={`button button-${variant} ${className}`}>{children}</button>;
}

function MetricCard({ icon, label, value, detail, tone }: { icon: IconName; label: string; value: string; detail: ReactNode; tone: string }) {
  return (
    <div className="metric-card">
      <div className={`metric-icon ${tone}`}><Icon name={icon} /></div>
      <div className="metric-copy">
        <div className="eyebrow">{label}</div>
        <div className="metric-value">{value}</div>
        <div className="metric-detail">{detail}</div>
      </div>
    </div>
  );
}

function Sidebar({ active, setActive, open, onClose, paymentCount, user, onLogout }: { active: string; setActive: (value: string) => void; open: boolean; onClose: () => void; paymentCount: number; user: ApiUser; onLogout: () => void }) {
  return (
    <>
      {open && <button type="button" className="sidebar-backdrop" onClick={onClose} aria-label="Cerrar menú" />}
      <aside className={`sidebar ${open ? "sidebar-open" : ""}`}>
        <div className="brand">
          <div className="brand-mark"><span /></div>
          <div className="brand-name">Cancha<span>Track</span></div>
          <Button variant="ghost" className="sidebar-close" onClick={onClose}><Icon name="close" /></Button>
        </div>
        <nav className="nav-list" aria-label="Navegación principal">
          <div className="nav-label">GESTIÓN DEL CLUB</div>
          {navItems.map((item) => (
            <button
              type="button"
              key={item.label}
              className={`nav-item ${active === item.label ? "nav-active" : ""}`}
              onClick={() => { setActive(item.label); onClose(); }}
            >
              <Icon name={item.icon} />
              <span>{item.label}</span>
              {item.label === "Pagos" && paymentCount > 0 && <span className="nav-badge">{paymentCount}</span>}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <button type="button" className={`nav-item ${active === "Configuración" ? "nav-active" : ""}`} onClick={() => { setActive("Configuración"); onClose(); }}><Icon name="settings" /><span>Configuración</span></button>
          <div className="profile-card">
            <div className="avatar avatar-dark">{user.fullName.split(/\s+/).slice(0, 2).map((word) => word[0]).join("").toUpperCase()}</div>
            <div><div className="profile-name">{user.fullName}</div><div className="profile-role">{roleLabel(user.roles)}</div></div>
          </div>
          <button type="button" className="nav-item" onClick={onLogout}><Icon name="arrow" /><span>Cerrar sesión</span></button>
        </div>
      </aside>
    </>
  );
}

export default function CanchaTrackApp({ teams, players, events, venues, user, onLogout, onCreateEvent, onRecordPayment }: {
  teams: TeamData[];
  players: PlayerData[];
  events: EventData[];
  venues: Venue[];
  user: ApiUser;
  onLogout: () => void;
  onCreateEvent: (input: NewEventInput) => Promise<ActionOutcome>;
  onRecordPayment: (playerId: string, chargeId: string, amount: number, playerName: string) => Promise<ActionOutcome>;
}) {
  const [isCreatingEvent, setIsCreatingEvent] = useState(false);
  const [isVerifyingPayment, setIsVerifyingPayment] = useState(false);
  const [active, setActive] = useState("Resumen");
  const [menuOpen, setMenuOpen] = useState(false);
  const [playerFilter, setPlayerFilter] = useState("Todos");
  const [toast, setToast] = useState("");
  const [settingsTab, setSettingsTab] = useState("Información del club");
  const [categories, setCategories] = useState(teams.map((item) => item.shortLabel));
  const [notificationPrefs, setNotificationPrefs] = useState<Record<string, boolean>>({
    "Pagos recibidos": true,
    "Pagos vencidos": true,
    "Recordatorios de partidos": true,
    "Cambios en el calendario": false,
  });
  const [disabledUsers, setDisabledUsers] = useState<string[]>([]);
  const [clubMenuOpen, setClubMenuOpen] = useState(false);
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  const [selectedTeam, setSelectedTeam] = useState(teams[0]?.label ?? "");
  const [unreadNotifications, setUnreadNotifications] = useState(3);
  const [eventModalOpen, setEventModalOpen] = useState(false);
  const [eventForm, setEventForm] = useState({ eventType: "TRAINING", venueId: venues[0]?.id ?? "", date: todayLocal(), time: "16:30", endTime: "18:00" });
  const [eventError, setEventError] = useState("");
  const [verifyingPlayerId, setVerifyingPlayerId] = useState("");
  const [calendarMonth, setCalendarMonth] = useState(todayLocal().slice(0, 7));
  const team = teams.find((item) => item.label === selectedTeam) ?? teams[0];
  const teamPlayers = players.filter((player) => player.categoryId === team?.id);
  const teamEvents = events.filter((event) => event.categoryId === team?.id);
  const pendingPlayers = teamPlayers.filter((player) => player.pending);
  const pendingCount = pendingPlayers.length;
  const blockedCount = Math.max(0, (team?.total ?? 0) - (team?.enabled ?? 0));
  const attendance = team?.attendance ?? [];
  const visiblePlayers = teamPlayers.filter((player) => playerFilter === "Todos" || (playerFilter === "Habilitados" ? player.enabled : !player.enabled));
  const firstName = user.fullName.split(/\s+/)[0] ?? "";

  const pageCopy: Record<string, [string, string]> = {
    Resumen: [`Hola, ${firstName}`, "Aquí tienes el pulso de tu equipo para hoy."],
    Jugadores: ["Jugadores", "Consulta la plantilla y el estado de habilitación de cada deportista."],
    Pagos: ["Pagos y cartera", "Confirma comprobantes y mantén la cartera del club bajo control."],
    Calendario: ["Calendario", "Entrenamientos, partidos y actividades del equipo en un solo lugar."],
    Reportes: ["Reportes", "Consulta el histórico deportivo y financiero del club."],
    Configuración: ["Configuración", "Administra la información del club y tus preferencias."],
  };
  const showToast = (message: string) => {
    setToast(message);
    window.setTimeout(() => setToast(""), 2600);
  };
  const changeTeam = (teamLabel: string) => {
    const nextTeam = teams.find((item) => item.label === teamLabel);
    setSelectedTeam(teamLabel);
    setClubMenuOpen(false);
    setPlayerFilter("Todos");
    const nextEvent = events.find((event) => event.categoryId === nextTeam?.id && event.date >= todayLocal());
    if (nextEvent) setCalendarMonth(nextEvent.date.slice(0, 7));
    showToast(`Ahora estás viendo ${teamLabel}`);
  };
  const submitEvent = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!team) return;
    if (!eventForm.venueId) {
      setEventError("Selecciona un escenario.");
      return;
    }
    if (eventForm.endTime <= eventForm.time) {
      setEventError("La hora de fin debe ser posterior a la de inicio.");
      return;
    }
    setEventError("");
    setIsCreatingEvent(true);
    const result = await onCreateEvent({
      categoryId: team.id,
      eventType: eventForm.eventType as "TRAINING" | "EVALUATION",
      venueId: eventForm.venueId,
      date: eventForm.date,
      time: eventForm.time,
      endTime: eventForm.endTime,
    });
    setIsCreatingEvent(false);
    if (!result.ok) {
      setEventError(result.error);
      return;
    }
    setCalendarMonth(eventForm.date.slice(0, 7));
    setEventModalOpen(false);
    showToast(result.message);
  };
  const handleVerifyPayment = async (player: PlayerData) => {
    if (!player.pending) return;
    setVerifyingPlayerId(player.id);
    setIsVerifyingPayment(true);
    const result = await onRecordPayment(player.id, player.pending.chargeId, player.pending.amount, player.name);
    setIsVerifyingPayment(false);
    setVerifyingPlayerId("");
    showToast(result.ok ? result.message : result.error);
  };
  const shiftCalendarMonth = (offset: number) => {
    const [year, month] = calendarMonth.split("-").map(Number);
    const next = new Date(Date.UTC(year, month - 1 + offset, 1));
    setCalendarMonth(`${next.getUTCFullYear()}-${String(next.getUTCMonth() + 1).padStart(2, "0")}`);
  };

  if (!team) {
    return <main className="empty-state"><strong>No hay categorías activas.</strong><span>Crea categorías en el backend o activa los datos de ejemplo (DEMO_DATA=true).</span></main>;
  }

  const [calendarYear, calendarMonthNumber] = calendarMonth.split("-").map(Number);
  const firstDayOffset = (new Date(Date.UTC(calendarYear, calendarMonthNumber - 1, 1)).getUTCDay() + 6) % 7;
  const daysInMonth = new Date(Date.UTC(calendarYear, calendarMonthNumber, 0)).getUTCDate();
  const monthTitle = new Intl.DateTimeFormat("es-CO", { month: "long", year: "numeric", timeZone: "UTC" })
    .format(new Date(Date.UTC(calendarYear, calendarMonthNumber - 1, 1)));
  const eventsThisMonth = teamEvents.filter((event) => event.date.startsWith(`${calendarMonth}-`));

  return (
    <div className="app-shell">
      <Sidebar active={active} setActive={setActive} open={menuOpen} onClose={() => setMenuOpen(false)} paymentCount={pendingCount} user={user} onLogout={onLogout} />
      <main className="main-content">
        <header className="topbar">
          <div className="mobile-brand">
            <Button variant="ghost" onClick={() => setMenuOpen(true)}><Icon name="menu" /></Button>
            <div className="brand-name">Cancha<span>Track</span></div>
          </div>
          <div className="topbar-action">
            <button type="button" className="club-switcher" aria-expanded={clubMenuOpen} onClick={() => { setClubMenuOpen(!clubMenuOpen); setNotificationsOpen(false); }}>
              <div className="club-crest">CD</div>
              <div><div className="club-name">Club Deportivo Norte</div><div className="club-team">{selectedTeam}</div></div>
              <Icon name="chevron" className={`size-4 rotate-90 ${clubMenuOpen ? "menu-chevron-open" : ""}`} />
            </button>
            {clubMenuOpen && <div className="top-popover club-popover">
              <div className="popover-heading"><strong>Cambiar equipo</strong><span>Club Deportivo Norte</span></div>
              {teams.map((option, index) => <button type="button" className={`team-option ${selectedTeam === option.label ? "team-selected" : ""}`} key={option.label} onClick={() => changeTeam(option.label)}>
                <div className={`team-option-mark avatar-${index === 1 ? "green" : index === 2 ? "purple" : "blue"}`}>{option.initials}</div>
                <div><strong>{option.label}</strong><span>{option.total} jugadores · {option.coach}</span></div>
                {selectedTeam === option.label && <Icon name="check" className="size-4" />}
              </button>)}
            </div>}
          </div>
          <div className="topbar-action">
            <button type="button" className="notification" aria-label="Notificaciones" aria-expanded={notificationsOpen} onClick={() => { setNotificationsOpen(!notificationsOpen); setClubMenuOpen(false); }}><Icon name="bell" />{unreadNotifications > 0 && <span />}</button>
            {notificationsOpen && <div className="top-popover notification-popover">
              <div className="popover-heading popover-heading-row"><div><strong>Notificaciones</strong><span>{unreadNotifications ? `${unreadNotifications} sin leer` : "Estás al día"}</span></div>{unreadNotifications > 0 && <button type="button" onClick={() => setUnreadNotifications(0)}>Marcar como leídas</button>}</div>
              <div className="notification-list">
                <button type="button" className={unreadNotifications > 0 ? "unread" : ""} onClick={() => { setActive("Pagos"); setNotificationsOpen(false); setUnreadNotifications(Math.max(0, unreadNotifications - 1)); }}><div className="notification-type orange"><Icon name="wallet" /></div><div><strong>Nuevo comprobante recibido</strong><span>Daniel Vargas envió su pago de agosto.</span><small>Hace 45 min</small></div></button>
                <button type="button" className={unreadNotifications > 1 ? "unread" : ""} onClick={() => { setActive("Calendario"); setNotificationsOpen(false); setUnreadNotifications(Math.max(0, unreadNotifications - 1)); }}><div className="notification-type blue"><Icon name="calendar" /></div><div><strong>Partido este sábado</strong><span>La convocatoria cierra mañana a las 6:00 PM.</span><small>Hace 2 h</small></div></button>
                <button type="button" className={unreadNotifications > 2 ? "unread" : ""} onClick={() => { setActive("Jugadores"); setNotificationsOpen(false); setUnreadNotifications(Math.max(0, unreadNotifications - 1)); }}><div className="notification-type green"><Icon name="check" /></div><div><strong>Asistencia actualizada</strong><span>El entrenamiento del miércoles alcanzó 96%.</span><small>Ayer</small></div></button>
              </div>
              <button type="button" className="popover-footer" onClick={() => { setActive("Configuración"); setSettingsTab("Notificaciones"); setNotificationsOpen(false); }}>Configurar notificaciones <Icon name="arrow" className="size-4" /></button>
            </div>}
          </div>
        </header>

        <div className="page">
          <section className="welcome-row">
            <div>
              <div className="page-title">{pageCopy[active][0]}</div>
              <div className="page-subtitle">{pageCopy[active][1]}</div>
            </div>
            {active === "Calendario" && <Button onClick={() => { setEventError(""); setEventModalOpen(true); }}><Icon name="plus" />Nuevo evento</Button>}
          </section>

          {active === "Resumen" ? <>
          <section className="metrics-grid">
            <MetricCard icon="users" label="JUGADORES ACTIVOS" value={`${team.total}`} tone="blue" detail={<span className="positive">{team.shortLabel} <span>· {team.coach}</span></span>} />
            <MetricCard icon="check" label="ASISTENCIA PROMEDIO" value={`${team.attendanceAverage}%`} tone="green" detail={<span className="positive">Equipo <span>seleccionado</span></span>} />
            <MetricCard icon="wallet" label="RECAUDO DEL MES" value={team.revenue} tone="purple" detail={<span><strong>{team.collection}%</strong> de lo que vence este mes</span>} />
            <MetricCard icon="alert" label="CARTERA PENDIENTE" value={team.debt} tone="orange" detail={<span className="warning-text">{pendingCount} cargos por cobrar</span>} />
          </section>

          <section className="content-grid">
            <div className="panel next-match">
              <div className="panel-heading">
                <div><div className="section-title">Próximo partido</div><div className="section-subtitle">{team.shortLabel}</div></div>
                {team.matchDate && <div className="date-pill"><span>{team.matchDate.weekday}</span><strong>{team.matchDate.day}</strong><small>{team.matchDate.month}</small></div>}
              </div>
              <div className="match-body">
                <div className="teams">
                  <div className="team"><div className="team-crest home-crest">CDN</div><span>Deportivo Norte</span></div>
                  <div className="match-info"><strong>{team.matchTime}</strong><span>{team.matchVenue}</span><div className="versus">VS</div></div>
                  <div className="team"><div className="team-crest away-crest">{team.opponent.split(" ").map(word => word[0]).join("").slice(0, 2)}</div><span>{team.opponent}</span></div>
                </div>
                <div className="eligibility">
                  <div className="eligibility-main">
                    <div className="status-icon"><Icon name="check" /></div>
                    <div><strong>{team.enabled} jugadores habilitados</strong><span>Activos y sin cargos vencidos</span></div>
                  </div>
                  <button type="button" className="text-link">Ver convocatoria <Icon name="arrow" className="size-4" /></button>
                </div>
                <div className="blocked-alert">
                  <Icon name="alert" />
                  <div><strong>{blockedCount} jugadores aún no están habilitados</strong><span>Tienen cargos vencidos o siguen en evaluación.</span></div>
                  <button type="button" onClick={() => setActive("Pagos")}>Revisar pagos</button>
                </div>
              </div>
            </div>

            <div className="panel attendance-panel">
              <div className="panel-heading">
                <div><div className="section-title">Asistencia reciente</div><div className="section-subtitle">Últimos 5 entrenamientos</div></div>
                <button type="button" className="text-link">Ver reporte <Icon name="arrow" className="size-4" /></button>
              </div>
              <div className="chart-area">
                <div className="chart-average"><strong>{team.attendanceAverage}%</strong><span>Promedio del equipo</span></div>
                <div className="bars">
                  {attendance.length === 0 && <div className="agenda-empty">Aún no hay entrenamientos con asistencia cerrada.</div>}
                  {attendance.map((item, index) => (
                    <div className="bar-item" key={`${item.date}-${index}`}>
                      <div className="bar-track"><div className={`bar-fill ${index === attendance.length - 1 ? "current" : ""}`} style={{ height: `${item.value}%` }}><span>{item.value}%</span></div></div>
                      <div className="bar-label"><strong>{item.day}</strong><span>{item.date}</span></div>
                    </div>
                  ))}
                </div>
              </div>
            </div>

            <div className="panel payments-panel">
              <div className="panel-heading">
                <div><div className="section-title">Pagos por cobrar</div><div className="section-subtitle">Cargo más antiguo pendiente de cada jugador</div></div>
                <button type="button" className="text-link" onClick={() => setActive("Pagos")}>Ver todos <Icon name="arrow" className="size-4" /></button>
              </div>
              <div className="payment-list">
                {pendingPlayers.length === 0 && <div className="agenda-empty">No hay cargos pendientes en esta categoría.</div>}
                {pendingPlayers.slice(0, 3).map((player) => (
                  <div className="payment-row" key={player.id}>
                    <div className={`avatar avatar-${player.tone}`}>{player.initials}</div>
                    <div className="player-copy"><strong>{player.name}</strong><span>{player.pending?.concept}</span></div>
                    <div className="payment-meta"><strong>{player.pending?.amountLabel}</strong><span><Icon name="clock" className="size-3" /> {player.pending?.overdue ? "Vencido" : "Vence"} {player.pending?.dueLabel}</span></div>
                    <Button variant="soft" disabled={isVerifyingPayment && verifyingPlayerId === player.id} onClick={() => handleVerifyPayment(player)}>{isVerifyingPayment && verifyingPlayerId === player.id ? "Registrando…" : "Registrar pago"}</Button>
                  </div>
                ))}
              </div>
            </div>

            <div className="panel players-panel">
              <div className="panel-heading">
                <div><div className="section-title">Estado de jugadores</div><div className="section-subtitle">Habilitación para competencia</div></div>
                <button type="button" className="text-link">Ver plantilla <Icon name="arrow" className="size-4" /></button>
              </div>
              <div className="players-list">
                {teamPlayers.length === 0 && <div className="agenda-empty">Esta categoría aún no tiene jugadores.</div>}
                {teamPlayers.map((player) => (
                  <div className="player-row" key={player.id}>
                    <div className={`avatar avatar-${player.tone}`}>{player.initials}</div>
                    <div className="player-copy"><strong>{player.name}</strong><span>Código {player.number}</span></div>
                    <span className={`status-chip ${player.enabled ? "status-ok" : "status-warn"}`}>{player.status}</span>
                  </div>
                ))}
              </div>
            </div>
          </section>
          </> : active === "Jugadores" ? (
            <section className="workspace-view">
              <div className="view-metrics">
                <div><span>Total plantilla</span><strong>{team.total}</strong></div>
                <div><span>Habilitados</span><strong className="green-copy">{team.enabled}</strong></div>
                <div><span>Con pagos pendientes</span><strong className="orange-copy">{pendingCount}</strong></div>
                <div><span>Asistencia promedio</span><strong>{team.attendanceAverage}%</strong></div>
              </div>
              <div className="panel">
                <div className="view-toolbar">
                  <div className="filter-group">
                    {["Todos", "Habilitados", "No habilitados"].map((filter) => <button key={filter} type="button" className={`filter-chip ${playerFilter === filter ? "filter-active" : ""}`} onClick={() => setPlayerFilter(filter)}>{filter}</button>)}
                  </div>
                  <span className="result-count">{visiblePlayers.length} jugadores</span>
                </div>
                <div className="data-table">
                  <div className="table-head"><span>JUGADOR</span><span>ASISTENCIA</span><span>MENSUALIDAD</span><span>ESTADO</span><span /></div>
                  {visiblePlayers.length === 0 && <div className="agenda-empty">No hay jugadores para este filtro.</div>}
                  {visiblePlayers.map((player) => (
                    <div className="table-row" key={player.id}>
                      <div className="table-player"><div className={`avatar avatar-${player.tone}`}>{player.initials}</div><div className="player-copy"><strong>{player.name}</strong><span>Código {player.number} · {team.shortLabel}</span></div></div>
                      <strong>{player.attendance}%</strong>
                      <span className={player.pending ? "orange-copy" : "green-copy"}>{player.pending ? (player.pending.overdue ? "Vencida" : "Pendiente") : "Al día"}</span>
                      <span className={`status-chip ${player.enabled ? "status-ok" : "status-warn"}`}>{player.enabled ? "Habilitado" : "No habilitado"}</span>
                      <Button variant="ghost" onClick={() => showToast(player.pending ? `${player.name} debe ${player.pending.amountLabel} (${player.pending.concept})` : `${player.name} está al día`)}><Icon name="chevron" className="size-4" /></Button>
                    </div>
                  ))}
                </div>
              </div>
            </section>
          ) : active === "Pagos" ? (
            <section className="workspace-view">
              <div className="view-metrics">
                <div><span>Recaudado este mes</span><strong>{team.revenue}</strong></div>
                <div><span>Por cobrar</span><strong className="orange-copy">{pendingCount} pagos</strong></div>
                <div><span>Cartera pendiente</span><strong>{team.debt}</strong></div>
                <div><span>Cumplimiento</span><strong className="green-copy">{team.collection}%</strong></div>
              </div>
              <div className="panel">
                <div className="panel-heading"><div><div className="section-title">Cargos pendientes</div><div className="section-subtitle">Registra la transferencia recibida para saldar el cargo más antiguo de cada jugador</div></div></div>
                <div className="payment-detail-list">
                  {pendingPlayers.length === 0 && <div className="agenda-empty">No hay cargos pendientes en esta categoría.</div>}
                  {pendingPlayers.map((player) => {
                    const isProcessing = isVerifyingPayment && verifyingPlayerId === player.id;
                    return <div className="payment-detail-row" key={player.id}>
                      <div className={`avatar avatar-${player.tone}`}>{player.initials}</div>
                      <div className="player-copy"><strong>{player.name}</strong><span>{player.pending?.concept}</span></div>
                      <div className="receipt"><Icon name="wallet" /><span>{player.pending?.overdue ? "Vencido" : "Vence"}<br /><strong>{player.pending?.dueLabel}</strong></span></div>
                      <strong>{player.pending?.amountLabel}</strong>
                      <Button variant="soft" disabled={isProcessing} onClick={() => handleVerifyPayment(player)}>{isProcessing ? "Registrando…" : "Registrar pago"}</Button>
                    </div>;
                  })}
                </div>
              </div>
            </section>
          ) : active === "Calendario" ? (
            <section className="calendar-layout">
              <div className="panel calendar-panel">
                <div className="calendar-title"><Button variant="ghost" onClick={() => shiftCalendarMonth(-1)}><Icon name="chevron" className="size-4 rotate-180" /></Button><strong className="calendar-month-name">{monthTitle}</strong><Button variant="ghost" onClick={() => shiftCalendarMonth(1)}><Icon name="chevron" className="size-4" /></Button></div>
                <div className="weekdays">{["LUN", "MAR", "MIÉ", "JUE", "VIE", "SÁB", "DOM"].map(day => <span key={day}>{day}</span>)}</div>
                <div className="calendar-grid">
                  {Array.from({ length: 42 }, (_, index) => {
                    const day = index - firstDayOffset + 1;
                    const valid = day > 0 && day <= daysInMonth;
                    const dayEvents = valid ? eventsThisMonth.filter((event) => Number(event.date.slice(8, 10)) === day) : [];
                    const dotType = dayEvents.some((event) => event.type === "match") ? "match" : dayEvents.some((event) => event.type === "other") ? "other" : dayEvents.length ? "training" : "";
                    return <button type="button" key={index} className="calendar-day"><span>{valid ? day : ""}</span>{dotType && <i className={`${dotType}-dot`} />}</button>;
                  })}
                </div>
                <div className="calendar-legend"><span><i className="training-dot" /> Entrenamiento</span><span><i className="match-dot" /> Partido</span><span><i className="other-dot" /> Otra actividad</span></div>
              </div>
              <div className="panel agenda-panel">
                <div className="panel-heading"><div><div className="section-title">Actividades del equipo</div><div className="section-subtitle">{teamEvents.length} eventos programados</div></div></div>
                <div className="agenda-list">
                  {teamEvents.length ? teamEvents.map(event => {
                    const date = new Date(event.date);
                    const weekday = new Intl.DateTimeFormat("es-CO", { weekday: "short", timeZone: "UTC" }).format(date).replace(".", "").toUpperCase();
                    return <div className="agenda-row" key={event.id}><div className="agenda-date"><span>{weekday}</span><strong>{String(date.getUTCDate()).padStart(2, "0")}</strong></div><i className={`${event.type}-line`} /><div><strong>{event.title}</strong><span>{event.time} · {event.location}</span></div></div>;
                  }) : <div className="agenda-empty">No hay actividades programadas para esta categoría.</div>}
                </div>
              </div>
            </section>
          ) : active === "Reportes" ? (
            <section className="workspace-view">
              <div className="report-cards">
                {[["chart", "Reporte de asistencia", "Histórico por jugador y entrenamiento", "Actualizado hoy"], ["wallet", "Estado de cartera", "Pagos recibidos, pendientes y vencidos", "Actualizado hace 1 h"], ["users", "Habilitación deportiva", "Jugadores disponibles para competencia", "Actualizado hoy"]].map((report) => <div className="panel report-card" key={report[1]}><div className="metric-icon blue"><Icon name={report[0] as IconName} /></div><div><strong>{report[1]}</strong><p>{report[2]}</p><span>{report[3]}</span></div><Button variant="soft" onClick={() => showToast(`${report[1]} descargado`)}>Descargar</Button></div>)}
              </div>
              <div className="panel report-summary">
                <div className="panel-heading"><div><div className="section-title">Resumen de los últimos 6 meses</div><div className="section-subtitle">Evolución consolidada del equipo</div></div></div>
                <div className="summary-bars">
                  {[["Mar", 72, 68], ["Abr", 78, 74], ["May", 75, 82], ["Jun", 84, 79], ["Jul", 82, 88], ["Ago", 87, 83]].map(item => <div className="summary-column" key={item[0]}><div><i style={{ height: `${item[1]}%` }} /><i style={{ height: `${item[2]}%` }} /></div><span>{item[0]}</span></div>)}
                </div>
                <div className="report-legend"><span><i /> Asistencia</span><span><i /> Recaudo</span></div>
              </div>
            </section>
          ) : (
            <section className="settings-grid">
              <div className="panel settings-nav">
                {["Información del club", "Categorías y equipos", "Notificaciones", "Usuarios y permisos"].map((item) => (
                  <button className={settingsTab === item ? "selected" : ""} type="button" key={item} onClick={() => setSettingsTab(item)}>{item}</button>
                ))}
              </div>
              <div className="panel settings-form">
                {settingsTab === "Información del club" ? <>
                  <div className="panel-heading"><div><div className="section-title">Información del club</div><div className="section-subtitle">Datos visibles para entrenadores y acudientes</div></div></div>
                  <div className="form-body">
                    <label>Nombre del club<input defaultValue="Club Deportivo Norte" /></label>
                    <label>Categoría principal<input defaultValue="Sub-15" /></label>
                    <label>Entrenador principal<input defaultValue="Carlos Ramírez" /></label>
                    <label>Ciudad<input defaultValue="Medellín" /></label>
                    <label className="form-wide">Correo de contacto<input defaultValue="contacto@deportivonorte.co" /></label>
                    <div className="form-actions"><Button variant="soft" onClick={() => showToast("Cambios descartados")}>Cancelar</Button><Button onClick={() => showToast("Información del club guardada")}>Guardar cambios</Button></div>
                  </div>
                </> : settingsTab === "Categorías y equipos" ? <>
                  <div className="panel-heading"><div><div className="section-title">Categorías y equipos</div><div className="section-subtitle">Organiza los grupos formativos del club</div></div><Button variant="soft" onClick={() => setCategories([...categories, `Sub-${11 - categories.length}`])}><Icon name="plus" /> Nueva categoría</Button></div>
                  <div className="settings-list">
                    {categories.map((category, index) => <div className="settings-row" key={`${category}-${index}`}>
                      <div className={`category-mark ${index % 2 ? "category-green" : ""}`}><Icon name="users" /></div>
                      <div className="settings-copy"><strong>{category}</strong><span>{index === 0 ? "24 jugadores · Carlos Ramírez" : index === 1 ? "18 jugadores · Laura Gómez" : "Sin jugadores · Entrenador por asignar"}</span></div>
                      <span className="status-chip status-ok">Activa</span>
                      <Button variant="ghost" onClick={() => showToast(`Editando categoría ${category}`)}><Icon name="chevron" className="size-4" /></Button>
                    </div>)}
                  </div>
                </> : settingsTab === "Notificaciones" ? <>
                  <div className="panel-heading"><div><div className="section-title">Notificaciones</div><div className="section-subtitle">Elige qué novedades quieres recibir</div></div></div>
                  <div className="settings-list">
                    {Object.entries(notificationPrefs).map(([label, enabled]) => <div className="settings-row" key={label}>
                      <div className="settings-copy"><strong>{label}</strong><span>{label.includes("Pagos") ? "Recibe avisos sobre movimientos de cartera y comprobantes." : "Mantente al tanto de novedades deportivas del equipo."}</span></div>
                      <button type="button" role="switch" aria-checked={enabled} className={`toggle ${enabled ? "toggle-on" : ""}`} onClick={() => setNotificationPrefs({ ...notificationPrefs, [label]: !enabled })}><span /></button>
                    </div>)}
                    <div className="notification-channel"><div><strong>Canal de entrega</strong><span>Las alertas se enviarán al correo registrado.</span></div><span>contacto@deportivonorte.co</span></div>
                  </div>
                  <div className="settings-footer"><Button onClick={() => showToast("Preferencias de notificación guardadas")}>Guardar preferencias</Button></div>
                </> : <>
                  <div className="panel-heading"><div><div className="section-title">Usuarios y permisos</div><div className="section-subtitle">Controla quién puede administrar la información del club</div></div><Button variant="soft" onClick={() => showToast("Invitación enviada por correo")}><Icon name="plus" /> Invitar usuario</Button></div>
                  <div className="settings-list">
                    {[["CR", "Carlos Ramírez", "carlos@deportivonorte.co", "Administrador", "blue"], ["LG", "Laura Gómez", "laura@deportivonorte.co", "Entrenadora", "green"], ["AM", "Andrea Moreno", "tesoreria@deportivonorte.co", "Tesorería", "purple"]].map(user => {
                      const disabled = disabledUsers.includes(user[1]);
                      return <div className={`settings-row ${disabled ? "user-disabled" : ""}`} key={user[1]}>
                        <div className={`avatar avatar-${user[4]}`}>{user[0]}</div>
                        <div className="settings-copy"><strong>{user[1]}</strong><span>{user[2]}</span></div>
                        <span className="role-chip">{user[3]}</span>
                        <Button variant="ghost" onClick={() => { setDisabledUsers(disabled ? disabledUsers.filter(name => name !== user[1]) : [...disabledUsers, user[1]]); showToast(disabled ? `${user[1]} reactivado` : `${user[1]} desactivado`); }}>{disabled ? "Reactivar" : "Desactivar"}</Button>
                      </div>;
                    })}
                  </div>
                </>}
              </div>
            </section>
          )}
        </div>
      </main>
      {eventModalOpen && (
        <div className="modal-layer" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !isCreatingEvent) setEventModalOpen(false); }}>
          <section className="player-modal" role="dialog" aria-modal="true" aria-labelledby="new-event-title">
            <div className="modal-heading">
              <div><div className="section-title" id="new-event-title">Nuevo evento</div><div className="section-subtitle">Se agregará al calendario de {team.shortLabel}</div></div>
              <button type="button" onClick={() => setEventModalOpen(false)} disabled={isCreatingEvent} aria-label="Cerrar"><Icon name="close" /></button>
            </div>
            <form className="player-form event-form" onSubmit={submitEvent}>
              <label>
                Tipo
                <select value={eventForm.eventType} onChange={(event) => setEventForm({ ...eventForm, eventType: event.target.value })}>
                  <option value="TRAINING">Entrenamiento</option>
                  <option value="EVALUATION">Evaluación</option>
                </select>
              </label>
              <label>
                Escenario
                <select value={eventForm.venueId} onChange={(event) => setEventForm({ ...eventForm, venueId: event.target.value })}>
                  {venues.length === 0 && <option value="">Sin escenarios</option>}
                  {venues.map((venue) => <option key={venue.id} value={venue.id}>{venue.name}</option>)}
                </select>
              </label>
              <label>
                Fecha
                <input type="date" value={eventForm.date} onChange={(event) => setEventForm({ ...eventForm, date: event.target.value })} required />
              </label>
              <label>
                Inicio
                <input type="time" value={eventForm.time} onChange={(event) => setEventForm({ ...eventForm, time: event.target.value })} required />
              </label>
              <label>
                Fin
                <input type="time" value={eventForm.endTime} onChange={(event) => setEventForm({ ...eventForm, endTime: event.target.value })} required />
              </label>
              {eventError && <div className="form-error form-wide"><Icon name="alert" />{eventError}</div>}
              <div className="modal-actions form-wide">
                <button type="button" className="button button-soft" onClick={() => setEventModalOpen(false)} disabled={isCreatingEvent}>Cancelar</button>
                <button type="submit" className="button button-primary" disabled={isCreatingEvent}>{isCreatingEvent ? "Guardando…" : "Crear evento"}</button>
              </div>
            </form>
          </section>
        </div>
      )}
      {toast && <div className="toast"><div><Icon name="check" /></div><span><strong>Acción completada</strong>{toast}</span><button type="button" onClick={() => setToast("")} aria-label="Cerrar"><Icon name="close" /></button></div>}
    </div>
  );
}
