import config from '@payload-config'
import { connection } from 'next/server'
import { getPayload } from 'payload'
import CanchaTrackApp, {
  type EventData,
  type PlayerData,
  type TeamData,
} from './CanchaTrackApp'

const money = new Intl.NumberFormat('es-CO', {
  currency: 'COP',
  maximumFractionDigits: 0,
  style: 'currency',
})

function initials(name: string) {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0])
    .join('')
    .toUpperCase()
}

export default async function HomePage() {
  await connection()
  const payload = await getPayload({ config })
  const [categoryResult, playerResult, eventResult] = await Promise.all([
    payload.find({
      collection: 'categories',
      limit: 100,
      overrideAccess: false,
      sort: 'name',
      where: { active: { equals: true } },
    }),
    payload.find({
      collection: 'players',
      depth: 0,
      limit: 100,
      overrideAccess: false,
      sort: 'fullName',
    }),
    payload.find({
      collection: 'events',
      depth: 0,
      limit: 100,
      overrideAccess: false,
      sort: 'date',
    }),
  ])

  const teamOrder = ['S15', 'S13', 'EF']
  const teams: TeamData[] = categoryResult.docs
    .map((category) => ({
      id: String(category.id),
      attendance: category.recentAttendance.map((item) => item.percentage),
      attendanceAverage: category.attendanceAverage,
      coach: category.coach,
      collection: category.collectionRate,
      debt: money.format(category.debt),
      enabled: category.enabledCount,
      initials: category.code,
      label: category.name,
      matchTime: category.matchTime,
      opponent: category.opponent,
      revenue: money.format(category.revenue),
      shortLabel: category.shortName,
      total: category.rosterSize,
    }))
    .sort((a, b) => teamOrder.indexOf(a.initials) - teamOrder.indexOf(b.initials))

  const players: PlayerData[] = playerResult.docs.map((player) => ({
    id: String(player.id),
    attendance: player.attendance,
    categoryId: String(player.category),
    initials: initials(player.fullName),
    name: player.fullName,
    number: String(player.jerseyNumber),
    status: player.paymentStatus === 'paid' ? 'Al día' : 'Pendiente',
    tone: player.tone,
  }))

  const events: EventData[] = eventResult.docs.map((event) => ({
    categoryId: String(event.category),
    date: event.date,
    id: String(event.id),
    location: event.location,
    time: event.time,
    title: event.title,
    type: event.eventType,
  }))

  return <CanchaTrackApp events={events} players={players} teams={teams} />
}
