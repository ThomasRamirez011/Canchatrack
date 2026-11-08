'use server'

import config from '@payload-config'
import { getPayload } from 'payload'

type CreatePlayerInput = {
  categoryId: string
  fullName: string
  jerseyNumber: string
}

type CreatePlayerResult =
  | { ok: true; playerName: string }
  | { ok: false; errors: Record<string, string> }

type CreateEventInput = {
  categoryId: string
  date: string
  eventType: string
  location: string
  time: string
  title: string
}

type ActionResult =
  | { ok: true; message: string }
  | { ok: false; errors: Record<string, string> }

export async function createPlayer(input: CreatePlayerInput): Promise<CreatePlayerResult> {
  const errors: Record<string, string> = {}
  const fullName = typeof input.fullName === 'string' ? input.fullName.trim() : ''
  const jerseyNumber = Number(input.jerseyNumber)
  const categoryId = typeof input.categoryId === 'string' ? input.categoryId.trim() : ''

  if (fullName.length < 3) errors.fullName = 'Escribe el nombre completo.'
  if (fullName.length > 80) errors.fullName = 'El nombre no puede superar 80 caracteres.'
  if (!/^[\p{L}][\p{L}\s'.-]+$/u.test(fullName)) {
    errors.fullName = 'Usa únicamente letras, espacios, apóstrofes o guiones.'
  }
  if (!Number.isInteger(jerseyNumber) || jerseyNumber < 1 || jerseyNumber > 99) {
    errors.jerseyNumber = 'El dorsal debe ser un número entre 1 y 99.'
  }
  if (!categoryId) errors.form = 'Selecciona una categoría válida.'
  if (Object.keys(errors).length > 0) return { ok: false, errors }

  try {
    const payload = await getPayload({ config })
    const category = await payload.findByID({
      collection: 'categories',
      id: categoryId,
      overrideAccess: false,
    })

    if (!category.active) {
      return { ok: false, errors: { form: 'La categoría seleccionada no está activa.' } }
    }

    const duplicate = await payload.find({
      collection: 'players',
      limit: 1,
      overrideAccess: false,
      where: {
        and: [
          { category: { equals: category.id } },
          { jerseyNumber: { equals: jerseyNumber } },
        ],
      },
    })

    if (duplicate.totalDocs > 0) {
      return {
        ok: false,
        errors: {
          jerseyNumber: 'Ese dorsal ya está asignado en la categoría seleccionada.',
        },
      }
    }

    const player = await payload.create({
      collection: 'players',
      data: {
        attendance: 0,
        category: category.id,
        fullName,
        jerseyNumber,
        paymentStatus: 'pending',
        tone: 'blue',
      },
      draft: false,
      overrideAccess: false,
    })

    return { ok: true, playerName: player.fullName }
  } catch (error) {
    const message = error instanceof Error ? error.message : ''
    if (message.includes('dorsal')) {
      return { ok: false, errors: { jerseyNumber: message } }
    }
    return {
      ok: false,
      errors: { form: 'No fue posible agregar el jugador. Inténtalo de nuevo.' },
    }
  }
}

export async function createEvent(input: CreateEventInput): Promise<ActionResult> {
  const errors: Record<string, string> = {}
  const title = typeof input.title === 'string' ? input.title.trim() : ''
  const eventType = ['training', 'match', 'other'].includes(input.eventType)
    ? input.eventType
    : ''
  const date = typeof input.date === 'string' ? input.date.trim() : ''
  const time = typeof input.time === 'string' ? input.time.trim() : ''
  const location = typeof input.location === 'string' ? input.location.trim() : ''
  const categoryId = typeof input.categoryId === 'string' ? input.categoryId.trim() : ''

  if (title.length < 3 || title.length > 80) {
    errors.title = 'Escribe un nombre de entre 3 y 80 caracteres.'
  }
  if (!eventType) errors.eventType = 'Selecciona un tipo de evento.'
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(Date.parse(`${date}T00:00:00Z`))) {
    errors.date = 'Selecciona una fecha válida.'
  }
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(time)) {
    errors.time = 'Selecciona una hora válida.'
  }
  if (location.length < 3 || location.length > 100) {
    errors.location = 'Escribe una ubicación de entre 3 y 100 caracteres.'
  }
  if (!categoryId) errors.form = 'Selecciona una categoría válida.'
  if (Object.keys(errors).length > 0) return { ok: false, errors }

  try {
    const payload = await getPayload({ config })
    const category = await payload.findByID({
      collection: 'categories',
      id: categoryId,
      overrideAccess: false,
    })
    if (!category.active) {
      return { ok: false, errors: { form: 'La categoría seleccionada no está activa.' } }
    }

    await payload.create({
      collection: 'events',
      data: {
        category: category.id,
        date: `${date}T${time}:00.000Z`,
        eventType: eventType as 'training' | 'match' | 'other',
        location,
        time,
        title,
      },
      draft: false,
      overrideAccess: false,
    })

    return { ok: true, message: `${title} fue agregado al calendario.` }
  } catch {
    return {
      ok: false,
      errors: { form: 'No fue posible crear el evento. Inténtalo de nuevo.' },
    }
  }
}

export async function verifyPayment(playerId: string): Promise<ActionResult> {
  const id = typeof playerId === 'string' ? playerId.trim() : ''
  if (!id) return { ok: false, errors: { form: 'El jugador no es válido.' } }

  try {
    const payload = await getPayload({ config })
    const player = await payload.findByID({
      collection: 'players',
      id,
      overrideAccess: false,
    })
    if (player.paymentStatus === 'paid') {
      return { ok: true, message: `El pago de ${player.fullName} ya estaba verificado.` }
    }

    await payload.update({
      collection: 'players',
      id: player.id,
      data: { paymentStatus: 'paid' },
      draft: false,
      overrideAccess: false,
    })

    return { ok: true, message: `Pago de ${player.fullName} verificado.` }
  } catch {
    return {
      ok: false,
      errors: { form: 'No fue posible verificar el pago. Inténtalo de nuevo.' },
    }
  }
}
