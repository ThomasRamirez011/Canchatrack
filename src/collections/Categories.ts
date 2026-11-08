import type { Access, CollectionConfig } from 'payload'

const isPayloadAdmin: Access = ({ req }) => req.user?.collection === 'admins'

export const Categories: CollectionConfig = {
  slug: 'categories',
  access: {
    create: isPayloadAdmin,
    delete: isPayloadAdmin,
    read: () => true,
    update: isPayloadAdmin,
  },
  admin: {
    group: 'Gestión deportiva',
    useAsTitle: 'name',
  },
  fields: [
    { name: 'name', type: 'text', required: true },
    { name: 'shortName', type: 'text', required: true },
    { name: 'code', type: 'text', required: true, unique: true, index: true },
    { name: 'coach', type: 'text', required: true },
    { name: 'active', type: 'checkbox', defaultValue: true, required: true },
    { name: 'rosterSize', type: 'number', required: true, min: 0 },
    { name: 'enabledCount', type: 'number', required: true, min: 0 },
    { name: 'attendanceAverage', type: 'number', required: true, min: 0, max: 100 },
    { name: 'revenue', type: 'number', required: true, min: 0 },
    { name: 'debt', type: 'number', required: true, min: 0 },
    { name: 'collectionRate', type: 'number', required: true, min: 0, max: 100 },
    { name: 'opponent', type: 'text', required: true },
    { name: 'matchTime', type: 'text', required: true },
    {
      name: 'recentAttendance',
      type: 'array',
      minRows: 5,
      maxRows: 5,
      required: true,
      fields: [
        { name: 'percentage', type: 'number', required: true, min: 0, max: 100 },
      ],
    },
  ],
}
