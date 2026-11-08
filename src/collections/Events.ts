import type { Access, CollectionBeforeValidateHook, CollectionConfig } from 'payload'

const isPayloadAdmin: Access = ({ req }) => req.user?.collection === 'admins'

const validateActiveCategory: CollectionBeforeValidateHook = async ({ data, req }) => {
  if (!data?.category) return data
  const categoryID =
    typeof data.category === 'object' && data.category !== null
      ? data.category.id
      : data.category
  const category = await req.payload.findByID({
    collection: 'categories',
    id: categoryID,
    overrideAccess: false,
    req,
  })
  if (!category.active) throw new Error('La categoría seleccionada no está activa.')
  return data
}

export const Events: CollectionConfig = {
  slug: 'events',
  access: {
    create: () => true,
    delete: isPayloadAdmin,
    read: () => true,
    update: isPayloadAdmin,
  },
  admin: {
    defaultColumns: ['title', 'eventType', 'date', 'category'],
    group: 'Gestión deportiva',
    useAsTitle: 'title',
  },
  hooks: {
    beforeValidate: [validateActiveCategory],
  },
  fields: [
    { name: 'title', type: 'text', required: true, minLength: 3, maxLength: 80 },
    {
      name: 'eventType',
      type: 'select',
      required: true,
      options: [
        { label: 'Entrenamiento', value: 'training' },
        { label: 'Partido', value: 'match' },
        { label: 'Otra actividad', value: 'other' },
      ],
    },
    { name: 'date', type: 'date', required: true, index: true },
    { name: 'time', type: 'text', required: true },
    { name: 'location', type: 'text', required: true, maxLength: 100 },
    {
      name: 'category',
      type: 'relationship',
      relationTo: 'categories',
      required: true,
      index: true,
    },
  ],
}
