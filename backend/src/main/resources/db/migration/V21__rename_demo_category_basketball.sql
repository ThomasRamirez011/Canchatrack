-- Corrección de dominio: CanchaTrack es una escuela de BALONCESTO (claude/CLAUDE.md).
-- El sembrado de demostración inicial creó la categoría «Escuela de Fútbol»; DemoDataSeeder ya usa
-- «Mini Baloncesto», pero no se vuelve a ejecutar sobre bases que ya tienen datos.
-- Solo renombra esa fila exacta y solo si el nombre nuevo está libre (name es UNIQUE).
-- No toca categorías con otros nombres ni bases nuevas (la tabla está vacía cuando corre la migración).
UPDATE categories
SET name = 'Mini Baloncesto'
WHERE name = 'Escuela de Fútbol'
  AND NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Mini Baloncesto');
