import type {
  Access,
  CollectionAfterChangeHook,
  CollectionBeforeValidateHook,
  CollectionConfig,
  FieldAccess,
} from 'payload'

const isPayloadAdmin: Access = ({ req }) => req.user?.collection === 'admins'
const adminFieldAccess: FieldAccess = ({ req }) => req.user?.collection === 'admins'
const paymentFieldAccess: FieldAccess = () => true

const validatePlayerCategoryAndNumber: CollectionBeforeValidateHook = async ({
  data,
  operation,
  req,
}) => {
  if (operation !== 'create' || !data) return data

  const categoryValue = data.category
  const categoryID =
    typeof categoryValue === 'object' && categoryValue !== null
      ? categoryValue.id
      : categoryValue

  if (!categoryID) throw new Error('Selecciona una categoría válida.')

  const category = await req.payload.findByID({
    collection: 'categories',
    id: categoryID,
    overrideAccess: false,
    req,
  })

  if (!category.active) throw new Error('La categoría seleccionada no está activa.')

  const duplicate = await req.payload.find({
    collection: 'players',
    limit: 1,
    overrideAccess: false,
    req,
    where: {
      and: [
        { category: { equals: categoryID } },
        { jerseyNumber: { equals: data.jerseyNumber } },
      ],
    },
  })

  if (duplicate.totalDocs > 0) {
    throw new Error('Ese dorsal ya está asignado en la categoría seleccionada.')
  }

  return data
}

const incrementCategoryRoster: CollectionAfterChangeHook = async ({
  doc,
  operation,
  req,
}) => {
  if (operation !== 'create') return doc
  const categoryID =
    typeof doc.category === 'object' && doc.category !== null
      ? doc.category.id
      : doc.category
  const category = await req.payload.findByID({
    collection: 'categories',
    id: categoryID,
    overrideAccess: true,
    req,
  })
  await req.payload.update({
    collection: 'categories',
    id: categoryID,
    data: { rosterSize: category.rosterSize + 1 },
    overrideAccess: true,
    req,
  })
  return doc
}

const protectPaymentVerification: CollectionBeforeValidateHook = ({
  data,
  operation,
  req,
}) => {
  if (operation !== 'update' || req.user?.collection === 'admins' || !data) return data
  if (data.paymentStatus !== 'paid') {
    throw new Error('Solo se permite verificar un pago pendiente.')
  }
  data.paymentVerifiedAt = new Date().toISOString()
  return data
}

const updateEnabledCount: CollectionAfterChangeHook = async ({
  doc,
  operation,
  previousDoc,
  req,
}) => {
  if (
    operation !== 'update' ||
    previousDoc?.paymentStatus !== 'pending' ||
    doc.paymentStatus !== 'paid'
  ) return doc

  const categoryID =
    typeof doc.category === 'object' && doc.category !== null
      ? doc.category.id
      : doc.category
  const category = await req.payload.findByID({
    collection: 'categories',
    id: categoryID,
    overrideAccess: true,
    req,
  })
  const collectionGoal =
    category.collectionRate > 0
      ? category.revenue / (category.collectionRate / 100)
      : category.revenue + category.debt
  const nextRevenue = category.revenue + 140000
  await req.payload.update({
    collection: 'categories',
    id: categoryID,
    data: {
      collectionRate: collectionGoal > 0
        ? Math.min(100, Math.round((nextRevenue / collectionGoal) * 100))
        : category.collectionRate,
      debt: Math.max(0, category.debt - 140000),
      enabledCount: Math.min(category.rosterSize, category.enabledCount + 1),
      revenue: nextRevenue,
    },
    overrideAccess: true,
    req,
  })
  return doc
}

export const Players: CollectionConfig = {
  slug: 'players',
  access: {
    create: () => true,
    delete: isPayloadAdmin,
    read: () => true,
    update: () => true,
  },
  admin: {
    group: 'Gestión deportiva',
    useAsTitle: 'fullName',
  },
  hooks: {
    afterChange: [incrementCategoryRoster, updateEnabledCount],
    beforeValidate: [validatePlayerCategoryAndNumber, protectPaymentVerification],
  },
  fields: [
    { name: 'fullName', type: 'text', required: true, minLength: 3, maxLength: 80 },
    { name: 'jerseyNumber', type: 'number', required: true, min: 1, max: 99 },
    {
      name: 'category',
      type: 'relationship',
      relationTo: 'categories',
      required: true,
      index: true,
    },
    {
      name: 'paymentStatus',
      type: 'select',
      defaultValue: 'pending',
      required: true,
      options: [
        { label: 'Al día', value: 'paid' },
        { label: 'Pendiente', value: 'pending' },
      ],
      access: { create: adminFieldAccess, update: paymentFieldAccess },
    },
    {
      name: 'paymentVerifiedAt',
      type: 'date',
      access: { create: adminFieldAccess, update: adminFieldAccess },
      admin: { readOnly: true },
    },
    {
      name: 'attendance',
      type: 'number',
      defaultValue: 0,
      required: true,
      min: 0,
      max: 100,
      access: { create: adminFieldAccess, update: adminFieldAccess },
    },
    {
      name: 'tone',
      type: 'select',
      defaultValue: 'blue',
      required: true,
      options: ['blue', 'green', 'orange', 'purple'],
      access: { create: adminFieldAccess, update: adminFieldAccess },
    },
  ],
}
