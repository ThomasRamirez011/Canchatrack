# CanchaTrack · Backend

Monolito modular (hexagonal) en **Java 21 + Spring Boot 3.5 + PostgreSQL ≥ 15 + Flyway**.
Proyecto académico: Universidad de Pamplona · Ingeniería de Software II. Fuentes de diseño: ERS v1.1, DAD v0.9, DDS v0.9.

## Arranque rápido

```bash
docker compose up -d                 # PostgreSQL 16 local (credenciales desechables de application-dev.yml)
mvn spring-boot:run                  # perfil "dev" por defecto; Flyway aplica V1..V11 al iniciar
curl localhost:8080/actuator/health  # {"status":"UP"}

mvn test                             # unitarias + arquitectura (sin Docker)
mvn verify                           # + integración con Testcontainers (requiere Docker)
./scripts/check-schema.sh            # solo esquema: migraciones + invariantes sobre un PostgreSQL ya disponible (PG* env)
```

## Estructura

```
src/main/java/co/edu/unipamplona/canchatrack/
  <modulo>/{api,domain,application,infrastructure}   11 módulos del DDS secc. 3
  shared/{config,domain,web,security}                núcleo mínimo: ErrorCode, correlación, problem+json, seguridad base
src/main/resources/
  db/migration/V1..V11                               modelo de datos del DDS secc. 7 (una migración por módulo)
  openapi/canchatrack-v1.yaml                        contrato OpenAPI 3.1 (se documenta aquí ANTES de implementar)
  application{,-dev,-prod}.yml                       perfiles; secretos solo por variables de entorno
src/test/                                            ArchUnit, unitarias, *IT (Testcontainers), schema-invariants.sql
```

Módulos: `identity`, `players` (incluye convocatoria, inscripción y comité), `documents`, `scheduling`, `attendance`,
`evaluations`, `competitions`, `payments`, `reports`, `notifications`, `audit`.
Regla de dependencia (verificada por `ArchitectureTest`): el dominio no usa frameworks; la aplicación no conoce
infraestructura; un módulo solo usa la fachada `.api` de otro; sin ciclos.

## Qué hay ya implementado

- **Esquema completo (46 tablas, 2 vistas)** con las reglas críticas impuestas en la base como última línea de defensa:
  pagos/ajustes/auditoría/versiones **append-only** (trigger `CT001`), asistencia **idempotente** (`client_uuid` y
  `(event_id, player_id)` únicos, lote por `Idempotency-Key`), sesión cerrada bloquea cambios (`CT002`), ronda cerrada
  bloquea decisiones (`CT003`), anulaciones coherentes (`CT004`), justificación obligatoria si el concepto no es favorable.
- `shared`: `ErrorCode` (los 14 códigos del DDS 13 + 7 adiciones), `DomainException`, `CorrelationIdFilter`,
  `ApiExceptionHandler` (`application/problem+json` con `code` y `correlationId`), `SecurityConfig` (sin estado, todo
  autenticado salvo health; **sin login todavía**).
- `audit`: puerto `AuditWriter` (`write` en la transacción de negocio; `writeIndependent` para intentos rechazados,
  p. ej. PA-01) y adaptador JDBC.

## Estado de verificación (sé honesto con esto)

| Qué | Cómo se verificó | Estado |
|---|---|---|
| Migraciones V1..V11 sobre PostgreSQL 16 real | `scripts/check-schema.sh` | Ejecutado: OK |
| Invariantes críticas (≈90 aserciones, incl. pagos, asistencia, unanimidad, auditoría) | `schema-invariants.sql`; se comprobó que **fallan** si se quitan los triggers | Ejecutado: OK |
| `ErrorCode` coincide con DDS 13, `DomainException`, `AuditRecord` | `javac` + ejecución | Ejecutado: OK |
| YAML (perfiles, compose), `pom.xml`, OpenAPI 3.1 | parseo / `openapi-spec-validator` | Ejecutado: OK |
| Código Spring (filtro, handler, seguridad, `JdbcAuditWriter`), `ArchitectureTest`, `*IT` | — | **No compilado ni ejecutado**: el entorno donde se generó no podía descargar dependencias de Maven Central ni tenía Docker. Primer `mvn verify` pendiente. |

## Decisiones que debe confirmar / discrepancias con los documentos

1. **Spring Boot 3.5.7** (no 4.x): no pude verificar coordenadas de 4.x. La línea 3.5 pierde soporte OSS a mediados de 2026;
   conviene migrar a 4.x cuando se pueda compilar. Subir al último parche: `mvn versions:update-parent`.
2. **Angular vs React**: DAD/DDS (DT-02, 6.1, 14, 15) dicen Angular; CLAUDE.md fija React. Documentos pendientes de actualizar.
3. **Estado E03** (pendiente de comité): está en el DAD 3.2 pero el DDS secc. 5 pasa de "radicada" a "categoría asignada"
   (T-01). El `CHECK` de `players.status` incluye `COMMITTEE_PENDING` (superconjunto); aclarar en el DDS.
4. **Módulos**: CLAUDE.md lista 7 contextos; el DDS secc. 3 define 11. Se siguió el DDS
   (inscripción→`players`; eventos/partidos→`scheduling`+`competitions`; evaluación/comité→`evaluations`+`players`).
5. **Tablas que el DDS secc. 7 no lista pero que sus reglas exigen**: `enrollments`, `call_exceptions` (RN-01/RF-044),
   `evaluator_assignments` (panel de 3), `document_types`/`document_windows` (RN-05, D-04), `attendance_sessions`
   (cierre), `sync_batches`, `attendance_corrections` (RN-15), `evaluation_rubrics` (DP-06), `evaluator_decision_history`,
   `charges` (sin cargos no hay saldo ni mora; ERS 7.3 "Cartera"), `venues`, `delivery_attempts`, `idempotency_keys`,
   `club_parameters`, `stored_files`. **Revisar `charges`**: el DDS no define de dónde sale lo adeudado.
6. **Diferencias menores con el DDS**: `event_participants` admite `player_id` (los menores no tienen cuenta) además de
   `UNIQUE(event_id,user_id,role)`; `audit_events.before/after` → `before_state/after_state` (palabras reservadas);
   `document_versions.type/number` → `document_type_id/version_number`.
7. **Pendientes del cliente (DP-01..DP-08)**: no se sembraron categorías, tarifas, mora, rúbrica ni vigencias; solo los
   valores que el DDS fija (alertas a 30 y 7 días; recordatorios a 24 h y 2 h).
8. **Provisional**: formato de códigos (`JG-000001`, `RAD-000001`), moneda por defecto `COP`.
9. **No impuesto en la base (lo valida el dominio)**: exactamente 3 evaluadores, ≥1 acudiente vigente, unanimidad
   (`UnanimityPolicy`), cruces de agenda (el DDS 11.2 admite "resolución explícita según política"), cupo de categoría,
   y las "acciones concretas" de RN-11 (la base exige solo la justificación, como el DDS).
10. Requiere **PostgreSQL ≥ 15** (`UNIQUE NULLS NOT DISTINCT` en `charges`).

## Convenciones

- Una migración Flyway por cambio de esquema; **nunca editar una ya aplicada** (las V1..V11 son la línea base:
  pueden ajustarse hasta el primer despliegue; después, solo migraciones nuevas).
- Los códigos `SQLSTATE` `CT001..CT004` deben traducirse a `ErrorCode` en el adaptador de persistencia.
- Secretos fuera del repositorio. En producción la aplicación usa una cuenta sin DDL y Flyway otra (`DB_MIGRATION_*`).
- Corrección de asistencia tras el cierre: en la misma transacción, `SELECT set_config('canchatrack.attendance_correction','on',true)`
  e insertar la fila en `attendance_corrections`.

## Siguiente paso (CLAUDE.md, punto 1)

Autenticación y roles: cuentas propias + OIDC opcional (DT-06), emisión/validación de JWT, roles desde `user_roles`,
autorización por vínculo con el jugador (PA-04, PA-14). Después: Dockerfile multi-etapa, pipeline CI con quality gates,
Swagger UI en `dev`, y los incrementos I2 en adelante.
