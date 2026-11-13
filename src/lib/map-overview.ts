import type { EventData, PlayerData, TeamData } from "@/app/(frontend)/CanchaTrackApp";
import { CLUB_TIME_ZONE, type ClubOverview } from "@/lib/api";

const money = new Intl.NumberFormat("es-CO", { currency: "COP", maximumFractionDigits: 0, style: "currency" });

export const formatMoney = (value: number) => money.format(value);

const tones = ["blue", "green", "orange", "purple"] as const;

function initialsOf(name: string) {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0])
    .join("")
    .toUpperCase();
}

/** «Sub-15» → «S15», «Mini Baloncesto» → «MB». */
function teamInitials(name: string) {
  const sub = /^sub[-\s]?(\d+)/i.exec(name.trim());
  if (sub) return `S${sub[1]}`;
  const letters = name
    .split(/\s+/)
    .filter((word) => word.length > 2)
    .map((word) => word[0]);
  return (letters.length ? letters.join("") : name).slice(0, 3).toUpperCase();
}

/** Fecha local del club como AAAA-MM-DD. */
export function localDate(iso: string) {
  return new Intl.DateTimeFormat("sv-SE", { timeZone: CLUB_TIME_ZONE }).format(new Date(iso));
}

export function localTime(iso: string) {
  return new Intl.DateTimeFormat("es-CO", { hour: "numeric", minute: "2-digit", timeZone: CLUB_TIME_ZONE }).format(new Date(iso));
}

function part(iso: string, options: Intl.DateTimeFormatOptions) {
  return new Intl.DateTimeFormat("es-CO", { ...options, timeZone: CLUB_TIME_ZONE })
    .format(new Date(iso))
    .replace(".", "");
}

export function mapOverview(overview: ClubOverview): { teams: TeamData[]; players: PlayerData[]; events: EventData[] } {
  const teams: TeamData[] = overview.teams.map((team) => ({
    id: team.categoryId,
    label: team.name,
    shortLabel: team.name,
    initials: teamInitials(team.name),
    total: team.rosterSize,
    enabled: team.enabledCount,
    attendanceAverage: team.attendanceAverage,
    revenue: formatMoney(team.monthRevenue),
    debt: formatMoney(team.debt),
    collection: team.collectionRate,
    opponent: team.nextMatch?.opponent ?? "Por definir",
    matchTime: team.nextMatch ? localTime(team.nextMatch.startAt) : "Sin partido programado",
    matchVenue: team.nextMatch?.venue ?? "—",
    matchDate: team.nextMatch
      ? {
          weekday: part(team.nextMatch.startAt, { weekday: "short" }).toUpperCase(),
          day: part(team.nextMatch.startAt, { day: "numeric" }),
          month: part(team.nextMatch.startAt, { month: "short" }).toUpperCase(),
        }
      : null,
    nextMatchEventId: team.nextMatch?.eventId ?? null,
    coach: team.headCoach ?? "Sin entrenador",
    coachId: team.headCoachId,
    attendance: team.recentAttendance.map((session) => ({
      day: part(session.startAt, { weekday: "short" }).replace(/^./, (c) => c.toUpperCase()),
      date: part(session.startAt, { day: "numeric" }),
      value: session.percentage,
    })),
  }));

  const players: PlayerData[] = overview.players.map((player, index) => ({
    id: player.playerId,
    categoryId: player.categoryId,
    initials: initialsOf(player.fullName),
    name: player.fullName,
    number: player.code,
    status: player.pendingCharge ? "Pendiente" : "Al día",
    tone: tones[index % tones.length],
    attendance: player.attendance,
    enabled: player.enabled,
    state: player.status,
    pending: player.pendingCharge
      ? {
          chargeId: player.pendingCharge.chargeId,
          amount: player.pendingCharge.outstanding,
          amountLabel: formatMoney(player.pendingCharge.outstanding),
          concept: `${player.pendingCharge.feeName} · ${part(`${player.pendingCharge.period}T12:00:00Z`, { month: "long" })}`,
          dueLabel: part(`${player.pendingCharge.dueDate}T12:00:00Z`, { day: "numeric", month: "short" }),
          overdue: player.pendingCharge.dueDate < localDate(overview.generatedAt),
        }
      : null,
  }));

  const events: EventData[] = overview.events
    .filter((event) => event.status !== "CANCELLED")
    .map((event) => {
      const isMatch = event.eventType === "FRIENDLY_MATCH" || event.eventType === "TOURNAMENT_MATCH";
      const title = isMatch
        ? `Partido vs ${event.opponent ?? "rival por definir"}`
        : event.eventType === "EVALUATION"
          ? "Evaluación"
          : "Entrenamiento";
      return {
        id: event.id,
        categoryId: event.categoryId,
        title,
        type: isMatch ? "match" : event.eventType === "EVALUATION" ? "other" : "training",
        date: localDate(event.startAt),
        time: localTime(event.startAt),
        location: event.venue,
      };
    });

  return { teams, players, events };
}
