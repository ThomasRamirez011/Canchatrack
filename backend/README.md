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
- `shared`: `ErrorCode` (los 14 códigos del DDS 13 + 8 adiciones), `DomainException`, `CorrelationIdFilter`,
  `ApiExceptionHandler` (`application/problem+json` con `code` y `correlationId`), `SecurityConfig` (sin estado; resource
  server JWT HS256; todo requiere token salvo health y login/refresh/logout; 401/403 también en `problem+json`).
- `identity` (autenticación y autorización, ver «Autenticación» abajo): login, renovación con rotación, cierre de sesión,
  `/auth/me`, alta de personal por el coordinador, bloqueo por intentos, `PlayerAccessPolicy` (acudiente-jugador).
- `players` (inscripción, ver «Inscripción» abajo): autorregistro de acudientes (en `identity`), convocatorias abiertas, radicación
  de inscripciones con consentimientos (en `documents`) y consulta de jugadores filtrada por `PlayerAccessPolicy`.
- `players` (administración, ver «Convocatorias y temporadas» abajo): el coordinador crea y activa temporadas; crea, edita,
  publica y cancela convocatorias y concede habilitaciones excepcionales con motivo (CU-01, RF-043, RF-044).
- `players` (categorías, ver «Categorías» abajo): el coordinador define categorías con rango de año de nacimiento y cupo
  (sin valores quemados: DP-01 sigue pendiente) y el sistema sugiere la categoría de un jugador (RF-046).
- `players` (comité, ver «Decisión del comité» abajo): el Comité Técnico asigna categoría, entrenador titular y panel de tres
  evaluadores (T-01) o rechaza con motivo (T-02), respaldado por un acta; cupo bajo concurrencia y auditoría (UC-02, RN-03, RF-045).
- `audit`: puerto `AuditWriter` (`write` en la transacción de negocio; `writeIndependent` para intentos rechazados,
  p. ej. PA-01) y adaptador JDBC.

## Estado de verificación (sé honesto con esto)

| Qué | Cómo se verificó | Estado |
|---|---|---|
| Migraciones V1..V14 sobre PostgreSQL 16 real | `scripts/check-schema.sh` | Ejecutado: OK |
| Invariantes críticas (≈90 aserciones, incl. pagos, asistencia, unanimidad, auditoría) | `schema-invariants.sql`; se comprobó que **fallan** si se quitan los triggers | Ejecutado: OK |
| `ErrorCode` coincide con DDS 13, `DomainException`, `AuditRecord` | `javac` + ejecución | Ejecutado: OK |
| YAML (perfiles, compose), `pom.xml`, OpenAPI 3.1 | parseo / `openapi-spec-validator` | Ejecutado: OK |
| Autenticación (V13, `identity`, JWT, `SecurityConfig`, `AuthIT`) | Migración e invariantes SQL en PostgreSQL 16 real (con mutaciones); dominio y casos de uso compilados y ejecutados con dobles en memoria | **Verificado:** `mvn test` (66 pruebas) y `mvn verify` (`AuthIT` incluida, con Testcontainers) en BUILD SUCCESS en Windows 11, JDK 24, Maven 3.10.0 |
| Inscripción (`players`, registro de acudientes, consentimientos, `EnrollmentIT`) | Todas las sentencias SQL de los adaptadores ejecutadas contra PostgreSQL 16 real; dominio y casos de uso compilados con `-Xlint:all` y ejecutados con dobles en memoria (58 comprobaciones); YAML OpenAPI validado | **Verificado:** `mvn test` y `mvn verify` (`EnrollmentIT` incluida, con Testcontainers) en BUILD SUCCESS en Windows 11, JDK 24, Maven 3.10.0 |
| Convocatorias y temporadas (`players`, `CallsAdminIT`) | Las sentencias SQL nuevas ejecutadas contra PostgreSQL 16 real con las migraciones V1..V13; servicios y reglas compilados y ejecutados con dobles en memoria (33 comprobaciones); YAML OpenAPI validado y referencias resueltas | **Verificado:** `mvn test` (143 pruebas) y `mvn verify` (50, `CallsAdminIT` incluida, con Testcontainers) en BUILD SUCCESS en Windows 11, JDK 24, Maven 3.10.0 |
| Categorías (V14, `players`, `CategoriesIT`) | Migración V14 y sus invariantes en PostgreSQL 16 real (`scripts/check-schema.sh`); todas las sentencias SQL nuevas ejecutadas contra esa base; servicios y reglas compilados y ejecutados con dobles en memoria (24 comprobaciones); OpenAPI validado con `openapi-spec-validator` | **Verificado:** `mvn test` (168 pruebas) y `mvn verify` (59, `CategoriesIT` incluida) en BUILD SUCCESS, tras corregir en la primera corrida un 422 que Bean Validation cortaba antes de acumular todos los errores de campo |
| Decisión del comité (`players`, `identity`/`documents` api, `CommitteeIT`) | Las sentencias SQL nuevas (bloqueo del jugador, cierre y alta de asignaciones, acta con asistentes `jsonb`, consulta de roles vigentes, fecha del club) ejecutadas contra PostgreSQL 16 real con V1..V14, incluido el cierre de un proceso anterior en otra transacción; reglas y servicio compilados y ejecutados con dobles en memoria (32 comprobaciones); OpenAPI validado | **Pendiente de `mvn test` y `mvn verify` locales** (el código Spring, los controladores y `CommitteeIT`, con su caso de concurrencia, no se pueden compilar en el entorno de desarrollo sin Maven Central) |
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
2. Las convocatorias y temporadas se administran con la API de «Convocatorias y temporadas» (abajo); sin una convocatoria
   publicada y abierta no se puede radicar.
3. **No se adjuntan documentos** en la radicación (`documents`) ni se envían notificaciones (`notifications`).
4. Si el jugador ya existe y es del mismo acudiente, se **ignoran** los nombres y la fecha de nacimiento enviados (manda el
   expediente existente); corregirlos será una operación aparte con auditoría.
5. Rangos de edad por categoría pendientes del cliente (DP-01): solo se rechazan fechas imposibles (futuras o de hace más de 100 años).
6. Tipos de documento provisionales (CC, CE, PA, TI, RC); el club puede ajustarlos.
7. Versión del texto de consentimiento: `canchatrack.enrollment.consent-version` (por defecto `v1`).

## Convocatorias y temporadas (players)

**Flujo.** El coordinador crea la temporada y la activa, crea la convocatoria (queda en borrador, invisible para los
acudientes), la publica y, si hace falta, mueve el cierre, la cancela o concede una habilitación excepcional a un acudiente
(DAD CU-01, ERS RF-043, RF-044, RN-01). Todo es solo para el rol `COORDINATOR`, salvo `GET /calls/open`.

| Endpoint | Notas |
|---|---|
| `POST /api/v1/seasons`, `GET /api/v1/seasons` | Crea una temporada `PLANNED` (nombre único) y lista, la más reciente primero. |
| `POST /api/v1/seasons/{id}/activate` | `PLANNED` → `ACTIVE`. 409 `RESOURCE_STATE_CONFLICT` en otro estado. |
| `POST /api/v1/calls`, `GET /api/v1/calls` | Crea en `DRAFT` (temporada existente y no cerrada); lista con filtros `seasonId` y `status` (máx. 200). |
| `GET /api/v1/calls/{id}` | Detalle, con `windowOpen` calculado por la base de datos. |
| `PATCH /api/v1/calls/{id}` | Parcial. Borrador: todo editable. Publicada: solo cierre e instrucciones, y mover el cierre exige `reason`. Cancelada: 409. |
| `POST /api/v1/calls/{id}/publish` | `DRAFT` → `PUBLISHED`; exige que el cierre aún no haya ocurrido. |
| `POST /api/v1/calls/{id}/cancel` | Exige `reason`. Cierra la recepción; **no anula** las inscripciones ya radicadas. |
| `POST /api/v1/calls/{id}/exceptions`, `GET …/exceptions` | Habilitación excepcional para un acudiente: motivo y vencimiento obligatorios, tope configurable. Solo en convocatorias publicadas. |

**Reglas.** Las transiciones usan `UPDATE … WHERE status = …` sobre la fila bloqueada (`FOR UPDATE`), de modo que dos
coordinadores no pueden publicar o cancelar a la vez. «Abierta», «cierre vencido» y «habilitación vigente» se evalúan con
`now()` de la base de datos, no con el reloj de la aplicación. Una habilitación no se edita ni se revoca: **caduca**
(`canchatrack.calls.max-exception-validity`, por defecto 30 días). Cada operación deja rastro de auditoría con estado anterior
y posterior cuando aplica (`SEASON_CREATED`, `SEASON_ACTIVATED`, `CALL_CREATED`, `CALL_UPDATED`, `CALL_PUBLISHED`,
`CALL_CANCELLED`, `CALL_EXCEPTION_GRANTED`); los resúmenes JSON se serializan con Jackson, así que un nombre con comillas no
rompe el documento. Código nuevo `RESOURCE_STATE_CONFLICT` (409) para transiciones no permitidas.

**Límites conocidos y pendientes (confirmar).**
1. **Cupos por categoría y documentos obligatorios** (RF-043) no se modelan: dependen de DP-01 (categorías, rangos y cupos) y
   DP-04 (documentos y vigencias), aún sin respuesta del cliente. Exigen una migración nueva.
2. **El cierre y la reapertura de temporadas** son el caso de uso CU-11 (RN-18) y no se exponen; no se impone una sola temporada
   activa porque el DAD no lo exige.
3. **Una habilitación excepcional no se puede revocar antes de su vencimiento** (la tabla es de solo concesión). Si el cliente lo
   pide, hay que añadir columnas de revocación con motivo.
4. La convocatoria no avisa a nadie al publicarse o cancelarse (`notifications` pendiente).
5. Los `@PreAuthorize` se evalúan después de validar el cuerpo: un usuario sin rol que envíe un cuerpo inválido recibe 422 y no
   403. No expone datos, pero conviene mover la autorización a `SecurityConfig` en una pasada transversal.

## Categorías (players)

**Por qué.** La decisión del comité asigna una categoría con cupo (RN-03, RN-04), pero el club aún no entregó rangos ni cupos
(DP-01). Por eso no hay valores quemados: el coordinador las define por API y pueden cambiarse sin desplegar.

| Endpoint | Acceso | Notas |
|---|---|---|
| `POST /api/v1/categories` | `COORDINATOR` | Nombre único, rango de años de nacimiento (1950-2100) y cupo (1-500). |
| `GET /api/v1/categories`, `GET /api/v1/categories/{id}` | `COORDINATOR`, `COMMITTEE` | Incluyen `occupied` y `available`. Por defecto solo activas (`includeInactive=true` para todas). |
| `PATCH /api/v1/categories/{id}` | `COORDINATOR` | Parcial. `clearNextCategory` quita el destino de ascenso. |
| `GET /api/v1/players/{id}/category-suggestion` | `COORDINATOR`, `COMMITTEE` | Categoría activa que cubre el año de nacimiento, con su cupo; `suggested: null` si ninguna. |

**Reglas.** Dos categorías **activas** no pueden cubrir el mismo año: la sugerencia sería ambigua. Lo impone la base con una
restricción de exclusión (V14, `int4range … WITH &&` solo sobre activas, sin extensiones) y el servicio lo anticipa para nombrar
la categoría en conflicto; si dos coordinadores chocan a la vez, la base responde y se informa igual como error de campo.
Desactivar libera el rango; reactivar exige que siga libre. El cupo no puede bajar de los jugadores ya asignados. El destino de
ascenso (`nextCategoryId`) no puede ser la misma categoría ni formar un ciclo. «Ocupado» cuenta las asignaciones vigentes de
jugadores que siguen en proceso o activos: no cuentan retirados, rechazados ni con temporada cerrada. La sugerencia no obliga:
el comité puede asignar otra categoría. Auditoría: `CATEGORY_CREATED`, `CATEGORY_UPDATED` (estado anterior y posterior).

**Límites conocidos y pendientes (confirmar).**
1. **Sin datos del club:** hasta que se resuelva DP-01 no hay categorías cargadas; el coordinador debe crearlas.
2. **`occupied` se alimenta con la decisión del comité** (ver «Decisión del comité»): cuenta las asignaciones vigentes.
3. **Una categoría inactiva conserva su rango** (historial); no hay borrado.
4. La sugerencia usa solo el **año** de nacimiento, como indica el DAD, no la fecha exacta.

## Decisión del comité (players)

**Por qué.** La inscripción radicada (E02) queda a la espera del Comité Técnico, que fija categoría, entrenador titular y el
panel que evaluará al jugador, o la rechaza (DDS UC-02, T-01 y T-02; RN-03, RF-045). Sin esta decisión no existe `occupied`
real, ni el panel de evaluadores que necesitan las rondas.

| Endpoint | Acceso | Notas |
|---|---|---|
| `POST /api/v1/players/{id}/assignments` | `COMMITTEE` | T-01: `SUBMITTED`/`COMMITTEE_PENDING` → `CATEGORY_ASSIGNED`. 201 con la asignación. |
| `POST /api/v1/players/{id}/rejections` | `COMMITTEE` | T-02: → `REJECTED`. Motivo obligatorio. |
| `GET /api/v1/players/{id}/assignment` | `COORDINATOR`, `COMMITTEE` | Categoría, entrenador y panel vigentes; 404 si no hay. |

**Acta (RN-03, RF-045).** Número (≤ 30), fecha de la reunión (no futura en la zona del club, `canchatrack.zone-id`, evaluada con
el reloj de la base), al menos un asistente y `minuteFileId`: un archivo **ya almacenado** en `stored_files`, validado con la
fachada `documents.api.StoredFileDirectory`. La misma acta no puede decidir dos veces al mismo jugador.

**Asignar.** El entrenador titular debe ser una cuenta **activa con rol COACH**. El panel son **exactamente tres** evaluadores
distintos, con cuenta activa y un rol admitido (`canchatrack.committee.panel-roles`, por defecto `EVALUATOR,COACH,COMMITTEE`; el
entrenador titular puede estar en el panel, porque el ERS lo describe como entrenador titular, preparador físico y delegado del
comité). Los roles se consultan con la fachada `identity.api.StaffDirectory` (vigencia con `now()` de la base). La categoría debe
existir y estar activa. **Cupo:** el jugador y la categoría se bloquean (`SELECT … FOR UPDATE`, primero el jugador y luego la
categoría) y se cuenta el cupo con una consulta posterior al bloqueo, así dos decisiones simultáneas no sobrepasan la categoría
(`CommitteeIT` lo prueba con dos hilos). Con la categoría llena responde **409 `CATEGORY_FULL`** (con `capacity` y `occupied`);
si `canchatrack.committee.allow-capacity-exception=true` el comité puede reenviar con `capacityExceptionReason` y la asignación
queda con ese motivo (también en la auditoría). El motivo solo se conserva si realmente se excede el cupo.

**Errores.** Todos los errores de campo se acumulan en un solo 422. Si alguno es del panel (`headCoachId`, `evaluatorIds`) el
código es `PANEL_INVALID`; en otro caso `VALIDATION_FAILED`. Un expediente que no está pendiente (incluida la repetición de la
misma solicitud) responde 409 `PLAYER_STATE_CONFLICT` sin escribir nada.

**Volver a decidir.** Si un jugador vuelve a radicar tras un proceso terminado, la nueva decisión **cierra** (`valid_to = now()`)
su categoría y panel anteriores antes de contar el cupo, así no cuenta contra sí mismo; el historial se conserva (RN-20).

Auditoría: `PLAYER_CATEGORY_ASSIGNED`, `PLAYER_ENROLLMENT_REJECTED` (estado anterior y posterior, motivo, rol `COMMITTEE`).

**Límites conocidos y pendientes (confirmar).**
1. **Valores provisionales (DP-01):** roles del panel y excepción de cupo son configurables porque el club no ha definido la
   composición real del panel (el sistema solo distingue COACH, EVALUATOR y COMMITTEE, no «preparador físico»).
2. **El acta se adjunta por referencia:** aún no hay endpoint de carga de archivos (módulo `documents`), así que `minuteFileId`
   debe apuntar a un `stored_files` existente. Cuando llegue la carga, se sube primero y luego se decide.
3. **Alcance de entrenador y evaluador:** esta decisión no abre aún el acceso del entrenador titular ni de los evaluadores al
   expediente (`PlayerAccessPolicy` los sigue denegando); es el incremento siguiente.
4. **Sin notificación** al acudiente de la decisión (módulo `notifications`) ni matrícula posterior (E05).
5. **Un acta por jugador y número:** la tabla permite un acta con varios jugadores (mismo número), pero cada decisión es una
   solicitud por jugador y repite los datos del acta.
6. La fecha «de hoy» usa la zona `canchatrack.zone-id` (America/Bogota por defecto).

## Siguiente paso

Siguiente incremento: el **alcance de entrenador y evaluador** en `PlayerAccessPolicy` (qué expedientes ven según su asignación
vigente) y, después, la **carga de archivos** del módulo `documents` (acta, documentos de inscripción). La **generación de cargos** (matrícula al aprobar la inscripción, mensualidades por mes)
llega con el incremento de pagos. Después: Dockerfile multi-etapa, pipeline CI con quality gates, Swagger UI en `dev`.
