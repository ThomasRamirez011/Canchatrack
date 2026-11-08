# CanchaTrack · Backend

Monolito modular (hexagonal) en **Java 21 + Spring Boot 3.5 + PostgreSQL ≥ 15 + Flyway**.
Proyecto académico: Universidad de Pamplona · Ingeniería de Software II. Fuentes de diseño: ERS v1.1, DAD v0.9, DDS v0.9.

## Arranque rápido

```bash
docker compose up -d                 # PostgreSQL 16 local (credenciales desechables de application-dev.yml)
mvn spring-boot:run                  # perfil "dev" por defecto; Flyway aplica V1..V13 al iniciar
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
  db/migration/V1..V13                               modelo de datos del DDS secc. 7 (una migración por módulo)
  openapi/canchatrack-v1.yaml                        contrato OpenAPI 3.1 (se documenta aquí ANTES de implementar)
  application{,-dev,-prod}.yml                       perfiles; secretos solo por variables de entorno
src/test/                                            ArchUnit, unitarias, *IT (Testcontainers), schema-invariants.sql
```

Módulos: `identity`, `players` (incluye convocatoria, inscripción y comité), `documents`, `scheduling`, `attendance`,
`evaluations`, `competitions`, `payments`, `reports`, `notifications`, `audit`.
Regla de dependencia (verificada por `ArchitectureTest`): el dominio no usa frameworks; la aplicación no conoce
infraestructura; un módulo solo usa la fachada `.api` de otro; sin ciclos.

## Qué hay ya implementado

- **Esquema completo (48 tablas, 3 vistas)** con las reglas críticas impuestas en la base como última línea de defensa:
  pagos/ajustes/auditoría/versiones **append-only** (trigger `CT001`), asistencia **idempotente** (`client_uuid` y
  `(event_id, player_id)` únicos, lote por `Idempotency-Key`), sesión cerrada bloquea cambios (`CT002`), ronda cerrada
  bloquea decisiones (`CT003`), anulaciones y ajustes coherentes de pagos y cargos (`CT004`), justificación obligatoria si el concepto no es favorable.
- `shared`: `ErrorCode` (los 14 códigos del DDS 13 + 7 adiciones), `DomainException`, `CorrelationIdFilter`,
  `ApiExceptionHandler` (`application/problem+json` con `code` y `correlationId`), `SecurityConfig` (sin estado; resource
  server JWT HS256; todo requiere token salvo health y login/refresh/logout; 401/403 también en `problem+json`).
- `identity` (autenticación y autorización, ver «Autenticación» abajo): login, renovación con rotación, cierre de sesión,
  `/auth/me`, alta de personal por el coordinador, bloqueo por intentos, `PlayerAccessPolicy` (acudiente-jugador).
- `players` (inscripción, ver «Inscripción» abajo): autorregistro de acudientes (en `identity`), convocatorias abiertas, radicación
  de inscripciones con consentimientos (en `documents`) y consulta de jugadores filtrada por `PlayerAccessPolicy`.
- `audit`: puerto `AuditWriter` (`write` en la transacción de negocio; `writeIndependent` para intentos rechazados,
  p. ej. PA-01) y adaptador JDBC.

## Estado de verificación (sé honesto con esto)

| Qué | Cómo se verificó | Estado |
|---|---|---|
| Migraciones V1..V13 sobre PostgreSQL 16 real | `scripts/check-schema.sh` | Ejecutado: OK |
| Invariantes críticas (≈90 aserciones, incl. pagos, asistencia, unanimidad, auditoría) | `schema-invariants.sql`; se comprobó que **fallan** si se quitan los triggers | Ejecutado: OK |
| `ErrorCode` coincide con DDS 13, `DomainException`, `AuditRecord` | `javac` + ejecución | Ejecutado: OK |
| YAML (perfiles, compose), `pom.xml`, OpenAPI 3.1 | parseo / `openapi-spec-validator` | Ejecutado: OK |
| Autenticación (V13, `identity`, JWT, `SecurityConfig`, `AuthIT`) | Migración e invariantes SQL en PostgreSQL 16 real (con mutaciones); dominio y casos de uso compilados y ejecutados con dobles en memoria | **Verificado:** `mvn test` (66 pruebas) y `mvn verify` (`AuthIT` incluida, con Testcontainers) en BUILD SUCCESS en Windows 11, JDK 24, Maven 3.10.0 |
| Inscripción (`players`, registro de acudientes, consentimientos, `EnrollmentIT`) | Todas las sentencias SQL de los adaptadores ejecutadas contra PostgreSQL 16 real; dominio y casos de uso compilados con `-Xlint:all` y ejecutados con dobles en memoria (58 comprobaciones); YAML OpenAPI validado | **Parcial:** el código Spring de esta parte y sus pruebas JUnit/`EnrollmentIT` **aún no se han compilado ni ejecutado**: pendiente `mvn test` y `mvn verify` |
| Código Spring (filtro, handler, seguridad, `JdbcAuditWriter`), `ArchitectureTest`, unitarias | `mvn test` en Windows 11, JDK 24 (compila con `--release 21`), Maven 3.10.0 | Ejecutado: OK (19 pruebas, 0 fallos) |
| `*IT` con Testcontainers (`DatabaseSchemaIT`, `AuditWriterIT`) | `mvn verify`, Docker Desktop 4.98 / Engine 29.9.2 | Ejecutado: OK (7 pruebas, 0 fallos) |

### Nota: Docker Desktop reciente + Testcontainers

Con Docker Engine 29.x, el Testcontainers que gestiona Spring Boot 3.5.7 puede fallar con
`Could not find a valid Docker environment`, porque habla una API de Docker más antigua que la mínima aceptada.
Solución local (no va en el repositorio): crear `%USERPROFILE%\.docker-java.properties` (en Linux/macOS `~/.docker-java.properties`) con

```
api.version=1.44
```

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
   `club_parameters`, `stored_files`. **`charges` (resuelto en V12)**: el DDS no definía de dónde sale lo adeudado; ver «Modelo de cobros» abajo.
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

- Una migración Flyway por cambio de esquema; **nunca editar una ya aplicada** (las V1..V13 son la línea base:
  pueden ajustarse hasta el primer despliegue; después, solo migraciones nuevas).
- Los códigos `SQLSTATE` `CT001..CT004` deben traducirse a `ErrorCode` en el adaptador de persistencia.
- Secretos fuera del repositorio. En producción la aplicación usa una cuenta sin DDL y Flyway otra (`DB_MIGRATION_*`).
- Corrección de asistencia tras el cierre: en la misma transacción, `SELECT set_config('canchatrack.attendance_correction','on',true)`
  e insertar la fila en `attendance_corrections`.

## Modelo de cobros (V12)

- **Cargos persistidos** (`charges`), no calculados al vuelo: un cambio de tarifa a mitad de temporada no altera la deuda
  histórica. Matrícula: un cargo único al aprobarse la inscripción (`period` NULL). Mensualidad: un cargo por mes
  (`period` = día 1). La **generación vive en la capa de aplicación** (aún sin implementar) y es idempotente gracias a
  `UNIQUE NULLS NOT DISTINCT (player_id, fee_id, period)`: `INSERT ... ON CONFLICT DO NOTHING`.
- Un cargo es **inmutable** (`CT001` si se edita o borra); solo admite una **anulación** única con motivo y autor.
- **Descuentos y exoneraciones** son asientos append-only en `charge_adjustments` (`DISCOUNT`, `EXEMPTION`, `CORRECTION`)
  con motivo obligatorio. Una `EXEMPTION` perdona exactamente lo pendiente (no lo ya pagado) y solo existe una por cargo;
  ninguna reducción puede dejar el cargo con saldo a favor. Violaciones → `CT004`.
- Un pago aplicado a un cargo debe ser del mismo jugador y tarifa y el cargo no puede estar anulado (`check_violation`).
- Vistas derivadas: `player_balances` (saldo por jugador) y `charge_balances` (estado por cargo: `VOIDED`, `SETTLED`,
  `PARTIAL`, `PENDING`).
- **Mora: fuera de alcance.** Solo se guarda `due_date`; días de gracia y recargos son un parámetro pendiente del cliente (DP-05).
  Tampoco se sembraron tarifas ni el día de vencimiento mensual.

## Autenticación (identity)

**Modelo.** Cuentas propias con correo y contraseña (BCrypt). Access token **JWT HS256 de 15 min** (`sub` = id de cuenta,
`roles`, `iss`, `jti`) + **refresh token opaco** de 7 días con **rotación**: cada uso lo reemplaza y reutilizar uno ya
canjeado **revoca toda la sesión**. Tope absoluto de sesión: 30 días. Los refresh tokens solo se guardan como SHA-256.
OIDC (DT-06) queda como extensión posterior (`users.oidc_subject` ya existe).

| Endpoint | Acceso | Notas |
|---|---|---|
| `POST /api/v1/auth/login` | público | Toda causa de rechazo responde igual (401): no revela qué cuentas existen. 5 fallos seguidos bloquean 15 min. |
| `POST /api/v1/auth/refresh` | público (con refresh token) | Rotación + detección de reutilización. |
| `POST /api/v1/auth/logout` | público (con refresh token) | Idempotente; 204 siempre. |
| `GET /api/v1/auth/me` | autenticado | Lee de la base: una cuenta no activa responde 401. |
| `POST /api/v1/users` | `COORDINATOR` | Alta de personal. **No** admite `GUARDIAN`: esas cuentas nacen con la inscripción. |

**Reglas de acceso a jugadores** (`identity.api.PlayerAccessPolicy`, la usarán los demás módulos): coordinador y comité,
todos; acudiente, solo con vínculo vigente en `player_guardians`; **entrenador y evaluador, ninguno todavía** (su alcance
depende de la categoría asignada y del panel de evaluación, que define `players`; lo no resuelto se deniega).

**Configuración (todo por entorno; ver `application*.yml`).**

| Variable | Dónde | Descripción |
|---|---|---|
| `JWT_SECRET` | **obligatoria en prod** | Base64 de ≥ 32 bytes. Generar: `openssl rand -base64 48`. Sin ella la aplicación no arranca. |
| `BOOTSTRAP_COORDINATOR_EMAIL` / `_PASSWORD` / `_NAME` | primer arranque | Crean el primer coordinador **solo si no hay ninguna cuenta**. Arrancar una vez, retirar las variables y cambiar la contraseña. |
| `canchatrack.identity.*` | opcional | `bcrypt-strength` (12), `refresh-ttl` (7d), `refresh-absolute-ttl` (30d), `max-failed-attempts` (5), `lock-duration` (15m). |

En **dev** hay una clave JWT y un coordinador desechables (`coordinador@canchatrack.local` / `Dev-Only-Change-Me-1`) para
poder probar sin configurar nada. **Nunca despliegue con el perfil por defecto (`dev`)**: use `SPRING_PROFILES_ACTIVE=prod`.

**Decisiones y límites conocidos (confirmar).**
1. **El access token no se puede revocar antes de vencer** (15 min): un cambio de roles o una cuenta inhabilitada surten efecto
   al renovar o vencer. `/auth/me` y la renovación sí consultan el estado actual. Si el cliente exige revocación inmediata,
   hay que añadir una lista de `jti` revocados o consultar la base en cada solicitud.
2. **Reutilización estricta:** dos renovaciones simultáneas con el mismo token (p. ej. doble clic) cuentan como reutilización y
   cierran la sesión. Una ventana de gracia de unos segundos es posible, a costa de debilitar la detección.
3. **Faltan** (no estaban pedidos todavía): recuperación y cambio de contraseña (dependen de notificaciones por correo),
   MFA del coordinador (DDS 12.1), limitación de tasa por IP, y limpieza periódica de refresh tokens vencidos (índice listo).
4. HS256 usa una clave simétrica: basta mientras un único servicio emite y valida. Si otro servicio debe validar sin poder
   emitir, migrar a RS256/ES256 sin cambiar el contrato.
5. El origen (IP) de auditoría depende de `server.forward-headers-strategy` (activo en prod) detrás del proxy inverso.

## Inscripción (players)

**Flujo.** El acudiente se registra, inicia sesión, consulta las convocatorias abiertas y radica la inscripción de su jugador
(DAD CU-02 / DDS UC-01). La radicación crea el expediente (estado `SUBMITTED`), vincula al acudiente como principal,
registra el consentimiento de datos y la autorización de participación (RF-011) y devuelve el código de radicación.

| Endpoint | Acceso | Notas |
|---|---|---|
| `POST /api/v1/guardians` | **público** | Autorregistro: cuenta con rol `GUARDIAN` + ficha de acudiente (CC, CE o PA). |
| `GET /api/v1/calls/open` | autenticado | Publicadas y dentro de su ventana, con el reloj de la **base de datos** (RN-01). |
| `POST /api/v1/enrollments` | `GUARDIAN` | Radica la inscripción. 409 `ENROLLMENT_CLOSED` si la convocatoria no está abierta y no hay habilitación excepcional vigente. |
| `GET /api/v1/players` | roles con alcance | Coordinador y comité: todos; acudiente: solo los vinculados; entrenador y evaluador: 403. Filtro `status`, paginación (`size` ≤ 100). |
| `GET /api/v1/players/{id}` | según `PlayerAccessPolicy` | Sin acceso y sin existencia responden igual (404). |

**Reglas.** Convocatoria publicada y abierta, o habilitación excepcional vigente del acudiente (`call_exceptions`; RN-01,
RF-044). Un jugador solo se radica de nuevo si su proceso anterior terminó (`REJECTED` o `SEASON_CLOSED`): se reutiliza su
expediente (RN-20); `WITHDRAWN` y cualquier proceso en curso dan 409. Los conflictos con un jugador existente responden
**siempre con el mismo mensaje genérico**, de modo que un acudiente no puede averiguar qué documentos de otras familias
existen. Todo rechazo de negocio deja rastro de auditoría en una transacción aparte (PA-01); la auditoría no guarda nombres,
documentos ni contactos. Las vigencias (convocatoria abierta, habilitaciones, vínculos) se evalúan con `now()` de la base de datos.

**Límites conocidos y pendientes (confirmar).**
1. **El registro público de acudientes no verifica el correo ni limita la tasa por IP**, y por ser un alta abierta revela si
   un correo o documento ya está registrado. Conviene añadir verificación por correo y límite de tasa antes de exponerlo.
2. **No hay administración de convocatorias ni temporadas todavía** (CU-01): hoy se crean por SQL o por el siguiente
   incremento. Sin una convocatoria abierta no se puede radicar.
3. **No se adjuntan documentos** en la radicación (`documents`) ni se envían notificaciones (`notifications`).
4. Si el jugador ya existe y es del mismo acudiente, se **ignoran** los nombres y la fecha de nacimiento enviados (manda el
   expediente existente); corregirlos será una operación aparte con auditoría.
5. Rangos de edad por categoría pendientes del cliente (DP-01): solo se rechazan fechas imposibles (futuras o de hace más de 100 años).
6. Tipos de documento provisionales (CC, CE, PA, TI, RC); el club puede ajustarlos.
7. Versión del texto de consentimiento: `canchatrack.enrollment.consent-version` (por defecto `v1`).

## Siguiente paso

Siguientes incrementos de `players`: **administración de convocatorias y temporadas** (CU-01) y **decisión del comité**
(acta, categoría con cupo, entrenador y panel de tres evaluadores; T-01/T-02), que además define el alcance de entrenador y
evaluador en `PlayerAccessPolicy`. La **generación de cargos** (matrícula al aprobar la inscripción, mensualidades por mes)
llega con el incremento de pagos. Después: Dockerfile multi-etapa, pipeline CI con quality gates, Swagger UI en `dev`.
