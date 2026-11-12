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
- `shared`: `ErrorCode` (los 14 códigos del DDS 13 + 9 adiciones), `DomainException`, `CorrelationIdFilter`,
  `ApiExceptionHandler` (`application/problem+json` con `code` y `correlationId`), `SecurityConfig` (sin estado; resource
  server JWT HS256; todo requiere token salvo health y login/refresh/logout; 401/403 también en `problem+json`; CORS por lista blanca, ver «CORS»).
- `identity` (autenticación y autorización, ver «Autenticación» abajo): login, renovación con rotación, cierre de sesión,
  `/auth/me`, alta de personal por el coordinador, bloqueo por intentos, `PlayerAccessPolicy` (acudiente-jugador y, por clase de dato, entrenador titular y evaluador asignado; ver «Alcance del personal deportivo»).
- `players` (inscripción, ver «Inscripción» abajo): autorregistro de acudientes (en `identity`), convocatorias abiertas, radicación
  de inscripciones con consentimientos (en `documents`) y consulta de jugadores filtrada por `PlayerAccessPolicy`.
- `players` (administración, ver «Convocatorias y temporadas» abajo): el coordinador crea y activa temporadas; crea, edita,
  publica y cancela convocatorias y concede habilitaciones excepcionales con motivo (CU-01, RF-043, RF-044).
- `players` (categorías, ver «Categorías» abajo): el coordinador define categorías con rango de año de nacimiento y cupo
  (sin valores quemados: DP-01 sigue pendiente) y el sistema sugiere la categoría de un jugador (RF-046).
- `players` (comité, ver «Decisión del comité» abajo): el Comité Técnico asigna categoría, entrenador titular y panel de tres
  evaluadores (T-01) o rechaza con motivo (T-02), respaldado por un acta; cupo bajo concurrencia y auditoría (UC-02, RN-03, RF-045).
- `documents` (archivos, ver «Pagos, matrícula y archivos»): carga y descarga de PDF/PNG/JPEG con validación de tamaño, tipo y
  firma del contenido; es lo que respalda actas y comprobantes.
- `payments` + `players` (matrícula y pagos, ver «Pagos, matrícula y archivos»): tarifas, cargo de matrícula, pagos inmutables
  con anulación, estado de cuenta, activación del jugador con la matrícula saldada y cobro mensual idempotente (CU-04, RN-16).
- `scheduling` + `attendance` (agenda y asistencia, ver «Agenda y asistencia»): escenarios, programación de entrenamientos y
  evaluaciones con detección de cruces (RN-14), asistencia con sincronización idempotente, cierre y corrección auditada (RN-15)
  y porcentaje por jugador (CU-05, CU-06).
- `evaluations` + `players` (evaluación y ascenso, ver «Evaluaciones y ascenso»): rondas de evaluación sobre un periodo, concepto independiente de cada uno de los tres evaluadores con justificación y acciones si no es favorable, cierre por unanimidad con plan de seguimiento y ascenso a la categoría siguiente con historial conservado (CU-07, CU-08, RN-08..RN-12).
- `competitions` + `scheduling` + `documents` + `payments` (partidos y convocatorias, ver «Partidos y convocatorias»): torneos, partidos con agenda sin cruces, convocatoria con elegibilidad por jugador (activo, certificado médico, autorización y mora), confirmación del coordinador, avisos a acudientes, autorización por partido y resultado (CU-09, RN-06, RN-07, RN-13).
- `documents` (documentos y vigencias, ver «Documentos y vigencias»): catálogo configurable de documentos, ventanas de carga, versiones inmutables, vigencia, acceso restringido y auditado a los documentos de salud, alertas de vencimiento a 30 y 7 días y exigencia de documentos obligatorios al activar al jugador (CU-13, RF-006..RF-010, RF-048, RN-05, RN-06, RN-17).
- `notifications` (procesador, ver «Procesador de notificaciones»): envía los avisos `PENDING` de la cola con reintentos de espera creciente,
  evidencia de cada intento y reclamo concurrente sin duplicados; remitente simulado en desarrollo y SMTP en producción.
- `scheduling` + `notifications` (avisos de agenda, ver «Avisos de agenda»): al programar o cancelar un entrenamiento o una evaluación se
  avisa por correo a los acudientes de la categoría y al entrenador, con invitación `.ics`; recordatorios 24 h y 2 h antes, sin repetirse (RF-016, RF-018 a RF-020).
- `scheduling` + `competitions` + `notifications` (reprogramación, ver «Reprogramación»): el coordinador cambia fecha, escenario, entrenador o indicaciones de un evento o de un partido confirmado, con motivo; el calendario recibe la misma cita actualizada, los acudientes un aviso y, en un partido, se revisa de nuevo la convocatoria (RF-017, RF-018).
- `audit`: puerto `AuditWriter` (`write` en la transacción de negocio; `writeIndependent` para intentos rechazados,
  p. ej. PA-01), adaptador JDBC y consulta de la bitácora con filtros y cursor (CU-14, ver «Auditoría y reportes»).
- `reports` (ver «Auditoría y reportes» e «Informe de temporada y cierre del expediente»): cartera por categoría con exportación a CSV y resumen de temporada, como modelo de lectura; informe PDF versionado por jugador y cierre o reapertura del expediente (CU-10, CU-11).

## Estado de verificación (sé honesto con esto)

| Qué | Cómo se verificó | Estado |
|---|---|---|
| Migraciones V1..V17 sobre PostgreSQL 16 real | `scripts/check-schema.sh` | Ejecutado: OK |
| Invariantes críticas (≈90 aserciones, incl. pagos, asistencia, unanimidad, auditoría) | `schema-invariants.sql`; se comprobó que **fallan** si se quitan los triggers | Ejecutado: OK |
| `ErrorCode` coincide con DDS 13, `DomainException`, `AuditRecord` | `javac` + ejecución | Ejecutado: OK |
| YAML (perfiles, compose), `pom.xml`, OpenAPI 3.1 | parseo / `openapi-spec-validator` | Ejecutado: OK |
| Autenticación (V13, `identity`, JWT, `SecurityConfig`, `AuthIT`) | Migración e invariantes SQL en PostgreSQL 16 real (con mutaciones); dominio y casos de uso compilados y ejecutados con dobles en memoria | **Verificado:** `mvn test` (66 pruebas) y `mvn verify` (`AuthIT` incluida, con Testcontainers) en BUILD SUCCESS en Windows 11, JDK 24, Maven 3.10.0 |
| Inscripción (`players`, registro de acudientes, consentimientos, `EnrollmentIT`) | Todas las sentencias SQL de los adaptadores ejecutadas contra PostgreSQL 16 real; dominio y casos de uso compilados con `-Xlint:all` y ejecutados con dobles en memoria (58 comprobaciones); YAML OpenAPI validado | **Verificado:** `mvn test` y `mvn verify` (`EnrollmentIT` incluida, con Testcontainers) en BUILD SUCCESS en Windows 11, JDK 24, Maven 3.10.0 |
| Convocatorias y temporadas (`players`, `CallsAdminIT`) | Las sentencias SQL nuevas ejecutadas contra PostgreSQL 16 real con las migraciones V1..V13; servicios y reglas compilados y ejecutados con dobles en memoria (33 comprobaciones); YAML OpenAPI validado y referencias resueltas | **Verificado:** `mvn test` (143 pruebas) y `mvn verify` (50, `CallsAdminIT` incluida, con Testcontainers) en BUILD SUCCESS en Windows 11, JDK 24, Maven 3.10.0 |
| Categorías (V14, `players`, `CategoriesIT`) | Migración V14 y sus invariantes en PostgreSQL 16 real (`scripts/check-schema.sh`); todas las sentencias SQL nuevas ejecutadas contra esa base; servicios y reglas compilados y ejecutados con dobles en memoria (24 comprobaciones); OpenAPI validado con `openapi-spec-validator` | **Verificado:** `mvn test` (168 pruebas) y `mvn verify` (59, `CategoriesIT` incluida) en BUILD SUCCESS, tras corregir en la primera corrida un 422 que Bean Validation cortaba antes de acumular todos los errores de campo |
| Decisión del comité (`players`, `identity`/`documents` api, `CommitteeIT`) | Las sentencias SQL nuevas (bloqueo del jugador, cierre y alta de asignaciones, acta con asistentes `jsonb`, consulta de roles vigentes, fecha del club) ejecutadas contra PostgreSQL 16 real con V1..V14, incluido el cierre de un proceso anterior en otra transacción; reglas y servicio compilados y ejecutados con dobles en memoria (32 comprobaciones); OpenAPI validado | **Verificado:** `mvn test` y `mvn verify` (`CommitteeIT` incluida, con su caso de concurrencia de dos hilos y Testcontainers) en BUILD SUCCESS en Windows 11, JDK 24, Maven 3.10.0 |
| Pagos, matrícula y archivos (`payments`, `documents`, `players`, `PaymentsIT`) | Las sentencias SQL nuevas (tarifa, cargo idempotente, bloqueo del cargo, pago parcial y total, anulación única, saldo, matrícula saldada, violación de FK) ejecutadas contra PostgreSQL 16 real con V1..V14; reglas compiladas y ejecutadas con `javac` (comprobaciones de `PaymentRules` y `FileRules`); OpenAPI validado | **Verificado:** `mvn test` y `mvn verify` (`PaymentsIT` incluida, con Testcontainers) en BUILD SUCCESS en Windows 11, JDK 24, Maven 3.10.0 |
| Agenda y asistencia (`scheduling`, `attendance`, `players.api`, `AgendaIT`) | Las sentencias SQL nuevas (cruces por escenario, entrenador y categoría con intervalos semiabiertos, plantilla por asignación vigente, lote idempotente por clave, marcas duplicadas, cierre, bloqueo por `CT002`, corrección con rastro, porcentaje) ejecutadas contra PostgreSQL 16 real con V1..V14; reglas y servicios compilados con `-Xlint:all`; OpenAPI validado con `openapi-spec-validator` | **Verificado:** `mvn test` y `mvn verify` (`AgendaIT` incluida, con Testcontainers) en BUILD SUCCESS en Windows 11, JDK 24, Maven 3.10.0 |
| Evaluaciones y ascenso (`evaluations`, `players.api`, `EvaluationsIT`) | Las sentencias SQL nuevas (una sola ronda abierta por jugador con `ON CONFLICT DO NOTHING`, alta/ajuste de concepto con `ON CONFLICT DO UPDATE` y rastro en el historial, vigencia del concepto por periodo, cambio de periodo, cierre, bloqueo `CT003` tras el cierre, cierre de la categoría anterior y alta de la asignación `PROMOTION`) ejecutadas contra PostgreSQL 16 real con V1..V14; capas de dominio y aplicación compiladas con `-Xlint:all`; OpenAPI validado con `openapi-spec-validator` | **Verificado:** `mvn test` y `mvn verify` (`EvaluationsIT` incluida, con Testcontainers) en BUILD SUCCESS en Windows 11, JDK 24, Maven 3.10.0, tras corregir en la primera corrida un nombre de esquema OpenAPI duplicado (`DecisionResponse`) |
| Partidos y convocatorias (`competitions`, `scheduling.api`, `documents.api`, `payments.api`, `notifications.api`, `CompetitionsIT`) | V15 y `schema-invariants.sql` sobre PostgreSQL 16 real (`scripts/check-schema.sh`); las sentencias SQL nuevas (proyección del partido, alta o sobrescritura de la propuesta sin tocar confirmados, confirmación, resultado, historial, vigencia del certificado, autorización de participación, mora, aviso con deduplicación, autorización por partido idempotente) ejecutadas contra esa base; dominio y aplicación compilados con `-Xlint:all`, reglas ejecutadas con una comprobación de humo; sintaxis de todos los fuentes nuevos y de las pruebas analizada con el parser de `javac`; OpenAPI validado y sin nombres de esquema duplicados | **Verificado:** `mvn test` y `mvn verify` (`CompetitionsIT` incluida, tras corregir la clave de deduplicación de avisos) en BUILD SUCCESS en Windows 11, JDK 24, Maven 3.10.0, Docker Desktop (2026-11-12) |
| Auditoría y reportes (V16, `audit`, `reports`, `AuditQueryIT`, `ReportsIT`) | V16 y `schema-invariants.sql` sobre PostgreSQL 16 real (`scripts/check-schema.sh`); todas las sentencias SQL nuevas (filtros de auditoría, paginación por `(occurred_at, id)` con su plan sobre `ix_audit_events_occurred`, cartera con totales por categoría, plantilla, agenda, asistencia, partidos y recaudo) ejecutadas contra esa base con datos de ejemplo; el montaje de datos de `ReportsIT` y `AuditQueryIT` repetido con los triggers reales y las cifras esperadas comprobadas; dominio y casos de uso compilados con `javac -Xlint:all` y ejecutados con dobles en memoria (≈85 comprobaciones: mora, porcentaje, CSV, reglas, cursor, paginación, rastro de la consulta, exportación auditada, truncado y «hoy» en la zona del club); OpenAPI validado con `openapi-spec-validator` (60 rutas, 102 esquemas, sin nombres repetidos) | **Verificado:** `mvn test` y `mvn verify` (`AuditQueryIT` y `ReportsIT` incluidas) en BUILD SUCCESS en Windows 11, JDK 24, Maven 3.10.0, Docker Desktop (2026-11-12) |
| Informe de temporada y cierre del expediente (V17, `reports`, `players`, `documents`, `SeasonClosureIT`, `SeasonReportRulesTest`, `SeasonReportPdfRendererTest`) | V17 y `schema-invariants.sql` sobre PostgreSQL 16 real (clave compuesta informe-jugador-temporada, un cierre vigente por temporada, reapertura de una sola vez, inmutabilidad); las siembras de `SeasonClosureIT` y todas las consultas del almacén (último cierre, asistencia, rondas, partidos, «cambió después del informe», inserciones con `RETURNING`) ejecutadas contra esa base con los triggers reales; dominio y casos de uso compilados con `javac -Xlint:all`; el PDF generado se comprobó con `qpdf --check`, `pypdf` y `pdftotext` (2 a 4 páginas, tildes y ñ, paréntesis y barras escapados, mismos bytes ante el mismo contenido) y las propiedades que afirma `SeasonReportPdfRendererTest` se reprodujeron sobre esos PDF; OpenAPI validado con `openapi-spec-validator` (65 rutas, 108 esquemas) | **Verificado:** `mvn test` y `mvn verify` (`SeasonClosureIT` incluida) en BUILD SUCCESS a la primera en Windows 11, JDK 24, Maven 3.10.0, Docker Desktop (2026-11-12) |
| Procesador de notificaciones (`notifications`, `NotificationRulesTest`, `NotificationsIT`) | Sin migración nueva (usa V11). Las sentencias SQL del reclamo (CTE con `FOR UPDATE SKIP LOCKED` y arriendo), del registro de resultado (guardas de estado e intento) y de la evidencia (`ON CONFLICT DO NOTHING`, inmutabilidad) ejecutadas sobre PostgreSQL 16 real con migraciones V1..V17: dos sesiones simultáneas reclamaron 20 y 20 avisos sin solaparse, un segundo reclamo no devolvió nada mientras duraba el arriendo y un resultado tardío no pisó al registrado; dominio y casos de uso compilados con `javac -Xlint:all`; las 18 pruebas de `NotificationRulesTest` ejecutadas con un sustituto mínimo de JUnit y AssertJ; configuración, remitentes y programador comprobados de tipos con sustitutos de Spring; sintaxis de `NotificationsIT` y del adaptador JDBC analizada con el parser de `javac`. **No** se probó contra un servidor SMTP real | **Verificado:** `mvn verify` en BUILD SUCCESS a la primera en Windows 11, JDK 24, Maven 3.10.0, Docker Desktop (2026-11-12): 298 pruebas unitarias y 127 de integración, 0 fallos (`NotificationsIT`: 11) |
| Documentos y vigencias (`documents`, `players`, `notifications`, `DocumentRulesTest`, `PlayerDocumentServiceTest`, `ExpiryAlertServiceTest`, `DocumentsIT`) | Sin migración nueva (usa V7). Todas las sentencias SQL del almacén (alta de tipo con arreglo `text[]` o `NULL`, numeración de versiones con subconsulta, vista de vigentes, trigger de solo inserción, documentos obligatorios faltantes, vencimientos en ventana con jugadores en curso, ventana general o del jugador, entrenador titular, archivo de salud) ejecutadas sobre PostgreSQL 16 real con V1..V17; dominio, aplicación y fachadas compilados con `javac -Xlint:all`; 16 pruebas de reglas y de servicio (ventana, vencimiento, acceso a salud, auditoría) y 3 de alertas ejecutadas con un sustituto mínimo de JUnit y AssertJ, más la plantilla de vencimiento en `NotificationRulesTest` (19 en total); OpenAPI validado (71 rutas, 116 esquemas, referencias resueltas). Controladores, adaptador JDBC y `DocumentsIT` **no** se compilaron contra Spring en el sandbox | **Verificado** el 12/11/2026 en el pipeline de GitHub Actions (PR #15): `mvn verify` en BUILD SUCCESS con 319 pruebas unitarias y 132 de integración, 0 fallos, incluidas `DocumentsIT` (5), `PaymentsIT` (4) y `OpenApiContractTest` (16); imagen Docker y smoke test en verde. La ejecución local en Windows 11 aportada por el usuario mostró 127 pruebas de integración, la cifra anterior a este incremento, así que no se cuenta como verificación de `DocumentsIT` |
| CORS (`shared.security`, `CorsOriginsTest`, `CorsIT`) | Reglas de la lista blanca (`CorsOrigins`, `CorsProperties`): 8 pruebas unitarias ejecutadas con un sustituto mínimo de JUnit y AssertJ; los 3 archivos nuevos y el modificado pasan el análisis sintáctico y los YAML son válidos. `CorsConfig`, `SecurityConfig` y `CorsIT` no se compilaron contra Spring en el sandbox | **Verificado** el 12/11/2026 en el pipeline de GitHub Actions (PR #16): `mvn verify` en BUILD SUCCESS con 327 pruebas unitarias y 139 de integración, 0 fallos, incluidas `CorsOriginsTest` (8) y `CorsIT` (7); imagen Docker y smoke test en verde |
| Alcance del personal deportivo (`identity`, `players`, `attendance`, `competitions`, `PlayerAccessServiceTest`, `PlayerQueryServiceTest`, `StaffScopeIT`) | Sin migración nueva. Las consultas de entrenador titular y evaluador vigentes y el filtro del listado ejecutados sobre PostgreSQL 16 real con V1..V17 (incluida la vigencia terminada); `PlayerAccessService` y `PlayerQueryService` compilados y 19 pruebas unitarias (12 de política, 7 de consulta) ejecutadas con un sustituto mínimo de JUnit y AssertJ; OpenAPI validado (71 rutas, 116 esquemas). Controladores, `JdbcPlayerStore`, `JdbcStaffAssignments` y `StaffScopeIT` no se compilaron contra Spring en el sandbox | **Verificado** el 12/11/2026 en el pipeline de GitHub Actions (PR #17): `mvn verify` en BUILD SUCCESS con 336 pruebas unitarias y 147 de integración, 0 fallos, incluidas `PlayerAccessServiceTest` (12), `PlayerQueryServiceTest` (7), `StaffScopeIT` (8) y `EnrollmentIT` (14); imagen Docker y smoke test en verde |
| Avisos de agenda (`scheduling`, `notifications`, `IcsCalendarTest`, `ReminderPolicyTest`, `AgendaNoticesTest`, `EventReminderServiceTest`, `SmtpEmailSenderTest`, `AgendaNotificationsIT`) | Sin migración nueva. Las dos consultas nuevas (contexto del evento con y sin entrenador, dirección o referencia del escenario; eventos confirmados de entrenamiento y evaluación dentro de la ventana del recordatorio, con su fecha de creación) ejecutadas sobre PostgreSQL 16 real con V1..V17; dominio y aplicación compilados con `javac -Xlint:all` y 52 pruebas unitarias (`IcsCalendarTest` 7, `ReminderPolicyTest` 9, `NotificationRulesTest` con las plantillas de evento, `AgendaNoticesTest` 8, `EventReminderServiceTest` 3) ejecutadas con un sustituto mínimo de JUnit y AssertJ; sintaxis de todos los fuentes analizada con el parser de `javac`. El adaptador JDBC, `SmtpEmailSender` (adjunto MIME), el programador y `AgendaNotificationsIT` **no** se compilaron contra Spring en el sandbox y **no** se probó contra un servidor SMTP real | **Verificado** el 12/11/2026 en el pipeline de GitHub Actions (PR #18): `mvn verify` en BUILD SUCCESS con 371 pruebas unitarias y 153 de integración, 0 fallos, incluidas `IcsCalendarTest` (7), `ReminderPolicyTest` (9), `AgendaNoticesTest` (8), `EventReminderServiceTest` (3), `SmtpEmailSenderTest` (2), `NotificationRulesTest` (25) y `AgendaNotificationsIT` (6); imagen Docker y smoke test en verde. Tres corridas: las dos primeras fallaron por errores de las pruebas nuevas (encabezados MIME sin `saveChanges()`, segundo acudiente marcado como principal, evento sembrado sin entrenador titular), no del código de producción |
| Reprogramación (`scheduling`, `competitions`, `notifications`, `AgendaNoticesTest`, `NotificationRulesTest`, `AgendaNotificationsIT`, `CompetitionsIT`) | Sin migración nueva. Las sentencias SQL nuevas (cruces con exclusión del propio evento, `UPDATE` de fecha, escenario y datos con la revisión + 1 y la guarda `status = 'CONFIRMED'`, cambio del entrenador titular, lectura de la revisión, `updated_at` actualizado por el trigger y usado como base de los recordatorios) ejecutadas sobre PostgreSQL 16 real con V1..V17; dominio y aplicación compilados con `javac -Xlint:all` y 44 pruebas unitarias (`AgendaNoticesTest`, `EventReminderServiceTest`, `NotificationRulesTest` con las plantillas de reprogramación y la secuencia de la cancelación) ejecutadas con un sustituto mínimo de JUnit y AssertJ; sintaxis de los 25 fuentes modificados analizada con el parser de `javac`; OpenAPI validado (73 rutas, 120 esquemas). Controladores, adaptador JDBC y las pruebas de integración nuevas **no** se compilaron contra Spring en el sandbox | **Verificado** el 12/11/2026 en el pipeline de GitHub Actions (PR #19): `mvn verify` en BUILD SUCCESS con 379 pruebas unitarias y 157 de integración, 0 fallos, incluidas `AgendaNoticesTest` (12), `NotificationRulesTest` (29), `AgendaNotificationsIT` (9) y `CompetitionsIT` (8); imagen Docker y smoke test en verde. Dos corridas: la primera falló en 3 pruebas de `AgendaNotificationsIT` por errores de las pruebas (asunto esperado distinto del que guarda la cola y un `UPDATE` que el trigger `set_updated_at` marcaba como modificación reciente), no del código de producción |
| Recordatorios de partidos y aviso al entrenador (`scheduling`, `competitions`, `notifications`, `AgendaNoticesTest`, `EventReminderServiceTest`, `NotificationRulesTest`, `ReminderPolicyTest`, `AgendaNotificationsIT`, `CompetitionsIT`) | Sin migración nueva. Las consultas nuevas (contexto del evento con el rival, jugadores confirmados de un evento-partido, eventos próximos con los cuatro tipos) ejecutadas sobre PostgreSQL 16 real con V1..V17; dominio y aplicación compilados con `javac -Xlint:all` y 62 pruebas unitarias ejecutadas con un sustituto mínimo de JUnit y AssertJ; sintaxis de los 23 fuentes modificados analizada con el parser de `javac`. Adaptador JDBC, `MatchAudienceService` y las pruebas de integración nuevas **no** se compilaron contra Spring en el sandbox | **Pendiente:** falta la corrida del pipeline de GitHub Actions |
| Contenedor e integración continua (`Dockerfile`, `.github/workflows/backend-ci.yml`) | El pipeline es su propia verificación: se ejecutó en GitHub Actions sobre el PR | **Verificado:** `mvn verify` (unitarias + integración con Testcontainers), construcción de la imagen y smoke test (health `UP`, login del coordinador de desarrollo, 401 sin token) en verde (2026-11-12). La primera ejecución falló por Docker Engine 29 (ver «Contenedor e integración continua») |
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

## CORS (shared.security)

El front (Next.js) corre en otro origen que la API, así que el navegador exige CORS. La política es una **lista blanca exacta**,
sin comodines y sin credenciales de navegador (la autenticación es el token en `Authorization`, nunca una cookie).

| Propiedad / variable | Valor |
|---|---|
| `canchatrack.security.cors.allowed-origins` / `CORS_ALLOWED_ORIGINS` | Orígenes separados por coma (`https://app.club.example`). **Vacío por defecto**: sin orígenes no se emite ninguna cabecera CORS. |
| Perfil `dev` | `http://localhost:3000` y `http://localhost:5173` si la variable no está definida. |
| Perfil `prod` | Solo lo que traiga `CORS_ALLOWED_ORIGINS`; sin valor, el navegador bloquea las llamadas entre orígenes. |

**Reglas.** Un origen es `esquema://host[:puerto]` sin ruta, barra final, consulta ni credenciales. Fuera de `localhost`,
`127.0.0.1` y `[::1]` se exige `https`. Un valor inválido (incluido `*`) **impide arrancar** y el mensaje enumera todos los problemas.

**Comportamiento.** Aplica a `/api/v1/**`. La verificación previa (`OPTIONS`) no exige token. Métodos `GET`, `POST`, `PUT`, `PATCH`,
`DELETE`, `OPTIONS`; cabeceras de solicitud `Authorization`, `Content-Type`, `Accept`, `Idempotency-Key` y `X-Correlation-Id`;
cabeceras legibles por el front `Content-Disposition` (descargas), `X-Correlation-Id`, `Retry-After` y `Location`; caché de la
verificación previa de 1 hora. Una solicitud de otro origen recibe 403 sin cabeceras CORS; las respuestas 401/403 al origen
autorizado sí las llevan para que el front pueda leer el error.

**Supuestos a confirmar.** El dominio real del front y si habrá más de un origen (por ejemplo, vista previa de Figma Make).

## Alcance del personal deportivo (identity, players, attendance, competitions)

Hasta ahora el entrenador y el evaluador no veían a ningún jugador. `PlayerAccessPolicy` conserva `canAccess` (acceso completo:
coordinador, comité y acudiente vinculado) y añade `canView(usuario, roles, jugador, PlayerData)`, el acceso **por clase de dato**.
Los módulos que exponen pagos, documentos o contacto siguen usando `canAccess`, así que el personal deportivo no los ve.

| Rol (con asignación vigente) | Datos básicos (`GET /players/{id}`) | Asistencia (`GET /players/{id}/attendance`) | Partidos (`GET /players/{id}/matches`) | Listado (`GET /players`) |
|---|---|---|---|---|
| Entrenador titular del jugador | Sí | Sí | Sí | Sus jugadores |
| Integrante del panel de evaluación (rol `EVALUATOR` o `COACH`) | Sí | No (403/404) | No (403/404) | Sus jugadores evaluados |
| Cualquiera de ellos sobre un jugador ajeno | 404 | 404 | 404 | No aparece |

**Datos básicos** = código, nombre, fecha de nacimiento, estado y fecha de alta. Para estos roles `documentType` y `documentNumber`
se omiten y `enrollments` va vacío (las inscripciones traen datos del acudiente). Pagos (`/account`), documentos y consentimientos
siguen cerrados; los documentos de salud del entrenador titular ya los resolvía `documents`. Sin acceso y sin existencia responden igual (404).

**Vigencia.** Entrenador titular = `category_assignments` vigente (`head_coach_id`); evaluador = `evaluator_assignments` vigente, ambas con
el reloj de la base de datos. El acceso termina al cerrar la asignación o al cambiar al jugador de categoría. Los roles salen del token
(15 min), como en el resto de la API. `identity` consulta esas dos tablas con SQL de solo lectura (`JdbcStaffAssignments`) para no depender de
las clases de `players`; un cambio en ellas obliga a revisar esas dos consultas.

**Supuestos a confirmar con el club.** (1) El evaluador solo ve datos básicos más su propia ronda (decisión del cliente en esta sesión).
(2) El entrenador ve asistencia y partidos de sus jugadores, no el estado de mora. (3) Un usuario con rol de acudiente y de personal
conserva el alcance de acudiente en el listado.

**Pendiente.** Mora como «sí/no» para el entrenador (decisión de esta sesión: no); evaluaciones y asistencia del periodo dentro de la ronda del evaluador.

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
2. **El cierre y la reapertura** se hacen por jugador (ver «Informe de temporada y cierre del expediente»); la temporada en sí no se cierra, y no se impone una sola temporada
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
2. **El acta se adjunta por referencia:** `minuteFileId` debe apuntar a un archivo ya subido con `POST /api/v1/files`; se sube primero y luego se decide.
3. **Alcance de entrenador y evaluador:** esta decisión no abre aún el acceso del entrenador titular ni de los evaluadores al
   expediente (`PlayerAccessPolicy` los sigue denegando); es el incremento siguiente.
4. **Sin notificación** al acudiente de la decisión (módulo `notifications`) ni matrícula posterior (E05).
5. **Un acta por jugador y número:** la tabla permite un acta con varios jugadores (mismo número), pero cada decisión es una
   solicitud por jugador y repite los datos del acta.
6. La fecha «de hoy» usa la zona `canchatrack.zone-id` (America/Bogota por defecto).

## Pagos, matrícula y archivos (payments, documents, players)

Alcance deliberadamente ligero: lo necesario para que el club cobre y active jugadores. Los montos y el calendario de cobro
no están definidos por el club (DP-05): las tarifas se cargan por API, sin valores en el código.

| Endpoint | Acceso | Notas |
|---|---|---|
| `POST /api/v1/files`, `GET /api/v1/files/{id}` | autenticado | Multipart `file`: PDF, PNG o JPEG, máx. 10 MB, firma del contenido verificada, nombre saneado. Descarga: quien lo subió, coordinador o comité. |
| `POST /api/v1/fees`, `GET /api/v1/fees` | `COORDINATOR` (GET también `COMMITTEE`) | Concepto `ENROLLMENT`, `MONTHLY` u `OTHER`; monto positivo con 2 decimales; COP. |
| `POST /api/v1/players/{id}/registration` | `COORDINATOR` | `CATEGORY_ASSIGNED` → `PENDING_REGISTRATION`; crea el cargo de matrícula con el monto de la tarifa de ese momento. |
| `POST /api/v1/players/{id}/payments` | `COORDINATOR` | Pago aplicado a un cargo; no puede superar lo que se debe; comprobante opcional (`receiptFileId`). |
| `POST /api/v1/payments/{id}/void` | `COORDINATOR` | Anulación con motivo y asiento compensatorio; el pago nunca se edita ni se borra (RN-16). |
| `GET /api/v1/players/{id}/account` | coordinador, comité, acudiente vinculado | Totales derivados (cargado, pagado, saldo), cargos con su estado y pagos. |
| `POST /api/v1/players/{id}/activation` | `COORDINATOR` | `PENDING_REGISTRATION` → `ACTIVE` solo con la matrícula saldada **y** los documentos obligatorios vigentes (si no, 409 con la lista `missingDocuments`; ver «Documentos y vigencias»). |
| `POST /api/v1/billing/monthly` | `COORDINATOR` | Un cargo por jugador activo para el periodo (primer día del mes); idempotente. |

**Decisiones.** (1) Los archivos se guardan en disco (`canchatrack.files.directory`, `./data/files` por defecto) detrás de un
puerto, de modo que pasar a un almacén de objetos no toca el negocio. (2) Un pago se aplica siempre a un cargo y se bloquea ese
cargo mientras se valida el saldo, así dos pagos simultáneos no lo sobrepasan. (3) `payments` no depende de `players`: es
`players` quien llama a `payments.api.EnrollmentBilling`. (4) Auditoría: `FEE_CREATED`, `ENROLLMENT_CHARGE_CREATED`,
`PAYMENT_RECORDED`, `PAYMENT_VOIDED`, `PLAYER_REGISTRATION_STARTED`, `PLAYER_ACTIVATED`, `MONTHLY_CHARGES_GENERATED`.

**Pendiente.** Antivirus de archivos (el contrato de error `FILE_REJECTED` ya lo contempla), ajustes parciales de pagos y de cargos
por API (el esquema los soporta), recordatorios de mora (`notifications`) y reportes de cartera.

## Documentos y vigencias (documents)

Catálogo de documentos que el club exige, ventanas de carga, versiones inmutables y alertas de vencimiento (CU-13). Las tablas
son las de V7 (sin migración nueva): `document_types`, `document_windows`, `document_versions` (solo inserción, trigger `CT001`)
y la vista `current_document_versions`.

| Endpoint | Acceso | Notas |
|---|---|---|
| `GET /api/v1/document-types` | autenticado | Qué documentos se exigen. Solo la coordinación ve los desactivados (`includeInactive=true`). |
| `POST /api/v1/document-types`, `PATCH /api/v1/document-types/{id}` | `COORDINATOR` | Código estable, obligatoriedad, si vence, vigencia en días, tamaño y formatos admitidos. Un tipo se desactiva, no se borra; el código y `containsHealthData` no cambian. |
| `POST /api/v1/document-windows`, `GET …` | `COORDINATOR` | Habilita la carga de un tipo durante un periodo, para un jugador o para todos. Auditado. |
| `GET /api/v1/players/{id}/documents` | coordinador, comité, entrenador titular, acudiente vinculado | Estado por tipo (`MISSING`, `VALID`, `EXPIRING_SOON`, `EXPIRED`), versión vigente y `uploadAllowed`. |
| `POST /api/v1/players/{id}/documents` | `GUARDIAN` vinculado, `COORDINATOR` | Multipart: `file`, `documentTypeId`, `issuedOn`, `expiresOn`, `notes`. Cada carga es una versión nueva. |
| `GET /api/v1/players/{id}/document-types/{typeId}/versions` | ídem | Historial completo, la más reciente primero. |
| `GET /api/v1/players/{id}/documents/versions/{versionId}/file` | ídem | Descarga como adjunto, sin caché y con `nosniff`. |

**Reglas.**
- **Carga (RN-05, RF-006).** El acudiente carga la primera versión siempre; una nueva solo con una ventana abierta o cuando la vigente
  está vencida o vence en 30 días o menos (si no, 409 `DOCUMENT_UPLOAD_NOT_ALLOWED`). La coordinación puede cargar en cualquier momento.
  Un documento que vence exige fecha de vencimiento futura o una vigencia definida en el tipo. Nada se reemplaza ni se borra (RF-008).
- **Acceso (RF-009, RN-17).** Los documentos de salud (`containsHealthData`) los ven solo la coordinación, el entrenador titular de la
  categoría del jugador y su acudiente; el comité ve los demás. Cada consulta de un documento de salud queda en la auditoría
  (`DOCUMENT_HEALTH_VIEWED`, sin nombre ni contenido del archivo). Quien no puede ver al jugador recibe 404; quien lo ve pero no el documento, 403.
  El mismo archivo **tampoco** se entrega al comité por `GET /files/{id}`.
- **Activación (RF-048, CA-02).** `players` consulta `documents.api.DocumentRequirements`: faltan los obligatorios activos sin versión
  vigente (o vencida si vence) → 409 `PLAYER_STATE_CONFLICT` con `missingDocuments`.
- **Alertas (RF-010, RN-06).** Un proceso diario (`canchatrack.documents.alerts.cron`, 06:00 hora del club; se apaga con
  `canchatrack.documents.alerts.enabled=false`) encola un aviso `DOCUMENT_EXPIRY` para el acudiente y el entrenador titular cuando faltan
  30 días o menos y otro cuando faltan 7 o menos. Se evalúa por tramo, no por día exacto: si el proceso no corrió un día no se pierde la alerta.
  La deduplicación (`tramo:fecha de vencimiento`) hace que repetir la corrida no duplique avisos y que un documento renovado avise de nuevo.
  El correo lleva el nombre del documento, el jugador y la fecha; nunca datos de salud.

**Supuestos que debe confirmar el club (D-04 sigue abierta).** (1) «Ante vencimiento» (RF-006) = vencido o a 30 días o menos de vencer.
(2) La primera versión de un documento se carga sin ventana (es el expediente que se está armando). (3) El acudiente vinculado puede ver
los documentos de salud de su propio hijo aunque RF-009 solo nombre a coordinador y entrenador. (4) Solo el certificado médico viene
sembrado (obligatorio, vence, salud); los demás documentos, sus vigencias y formatos los carga la coordinación desde el catálogo.

**Pendiente.** Adjuntar los documentos iniciales dentro de la radicación (CU-02) en lugar de cargarlos después; alerta de documentos
vencidos para el coordinador; antivirus.

## Agenda y asistencia (scheduling, attendance)

Alcance ligero: lo necesario para programar sesiones y pasar lista, también desde un dispositivo con conexión intermitente.

| Endpoint | Acceso | Notas |
|---|---|---|
| `POST /api/v1/venues`, `GET /api/v1/venues` | `COORDINATOR` (GET también `COMMITTEE`, `COACH`) | Nombre único. |
| `POST /api/v1/events` | `COORDINATOR` | Entrenamiento o evaluación, a futuro, con entrenador `COACH` y escenario activo. Cruce de escenario, entrenador o categoría: 409 `SCHEDULE_CONFLICT` con la lista `conflicts`. |
| `GET /api/v1/events`, `GET /api/v1/events/{id}` | coordinador y comité (todos), entrenador (los suyos) | Filtros `categoryId`, `from`, `to`; máx. 200. |
| `POST /api/v1/events/{id}/cancel` | `COORDINATOR` | Motivo obligatorio; libera el horario. |
| `GET /api/v1/events/{id}/attendance` | coordinador, comité, entrenador del evento | Plantilla activa de la categoría con la marca de cada jugador. |
| `POST /api/v1/events/{id}/attendance` | coordinador, entrenador del evento | Lote de marcas con `Idempotency-Key`; reintento = mismo resultado (`replayed`), marca repetida = duplicada, inválida o ajena a la plantilla = rechazada una a una. |
| `POST /api/v1/events/{id}/attendance/close` | ídem | Los sin marca quedan ausentes; la sesión se bloquea (RN-15). |
| `POST /api/v1/events/{id}/attendance/corrections` | ídem | Solo con la sesión cerrada, con justificación; deja rastro en `attendance_corrections` y en auditoría. |
| `GET /api/v1/players/{id}/attendance` | coordinador, comité, acudiente vinculado | Porcentaje = (presentes + tardes) / (sesiones cerradas − excusas aceptadas), un decimal. |

**Decisiones.** (1) RN-14 incluye al jugador: como cada jugador pertenece a una sola categoría, el cruce de categoría lo cubre.
(2) Las solicitudes simultáneas de programación se serializan con bloqueos consultivos por escenario, entrenador y categoría, en orden
fijo para no provocar interbloqueos. (3) La marca toma la sesión en modo compartido y el cierre en exclusivo: un cierre no se cruza
con marcas en curso, y el trigger `CT002` sigue siendo la última defensa. (4) La plantilla se obtiene por `players.api.CategoryRoster`
(asignación vigente y estado `ACTIVE` o `IN_EVALUATION`). (5) La fórmula del porcentaje no está definida en los documentos del club
(DP pendiente): es una propuesta documentada. (6) Auditoría: `VENUE_CREATED`, `EVENT_SCHEDULED`, `EVENT_CANCELLED`,
`ATTENDANCE_SESSION_CLOSED`, `ATTENDANCE_CORRECTED`.

**Pendiente.** Sesiones recurrentes (RF-014) y calendario con filtros por entrenador y escenario. Los avisos por correo con `.ics` ya están (ver
«Avisos de agenda»), la reprogramación también (ver «Reprogramación») y los partidos viven en `competitions`.

## Evaluaciones y ascenso (evaluations, players)

Alcance ligero: el ciclo completo ronda → conceptos → cierre → ascenso, sin rúbrica configurable (DP-06 sigue pendiente).

| Endpoint | Acceso | Notas |
|---|---|---|
| `POST /api/v1/players/{id}/evaluation-rounds` | `COORDINATOR` | Jugador `ACTIVE` con exactamente tres evaluadores vigentes (si no, 422 `PANEL_INVALID`). Pasa a `IN_EVALUATION`; solo una ronda abierta por jugador. `dueAt` obligatorio y futuro. |
| `GET /api/v1/players/{id}/evaluation-rounds` | coordinador, comité; evaluador del panel | Sin conceptos. Quien no integra el panel recibe 404. |
| `GET /api/v1/evaluation-rounds/{id}` | ídem | Coordinador y comité ven todos los conceptos y la vista previa `outcome`; el evaluador solo el suyo mientras la ronda está abierta. |
| `PUT /api/v1/evaluation-rounds/{id}/decision` | `EVALUATOR`, `COACH`, `COMMITTEE` y además integrante del panel | Crea o ajusta el concepto propio. `FOLLOW_UP_REQUIRED` o `UNFAVORABLE` exigen `justification` y `actions` (422 `NEGATIVE_REASON_REQUIRED`). Ronda cerrada: 409 `ROUND_CLOSED`. |
| `PUT /api/v1/evaluation-rounds/{id}/period` | `COORDINATOR` | Con motivo. Los conceptos del periodo anterior dejan de contar (`current` falso). |
| `POST /api/v1/evaluation-rounds/{id}/close` | `COORDINATOR` | Tres favorables vigentes: `APPROVED` y jugador `PROMOTION_APPROVED`. Si no: `FOLLOW_UP`, `followUpPlan` obligatorio y jugador de vuelta a `ACTIVE`. Con conceptos faltantes y la ronda vigente: 409. |
| `POST /api/v1/players/{id}/promotion` | `COORDINATOR` | Jugador `PROMOTION_APPROVED`. Sin `targetCategoryId` usa la categoría siguiente; exige cupo (409 `CATEGORY_FULL`) y entrenador `COACH`. Cierra la asignación anterior sin borrarla y conserva el panel. |

**Decisiones.** (1) La unanimidad es una función pura (`EvaluationRules.outcome`): menos de tres conceptos vigentes = pendiente; tres
favorables = aprobado; el resto = seguimiento (DDS 5.1). (2) El cierre lo ejecuta la coordinación y no el sistema al llegar el
tercer concepto: así el evaluador puede ajustar su decisión hasta el «cierre formal» (DAD CU-07). (3) Un concepto pendiente al vencimiento
cierra en seguimiento (RN-12); por eso `dueAt` es obligatorio. (4) La independencia de criterio se aplica en la lectura: un
evaluador no ve los conceptos de los demás hasta que la ronda cierra. (5) RN-10 se cumple por ausencia de operación y por el trigger
`CT003`: ni la coordinación ni nadie modifica un concepto emitido. (6) `evaluations` no toca las tablas de `players`: usa la
fachada `players.api.EvaluationGateway` (estado del jugador, panel y categoría), que bloquea el expediente y serializa abrir, cerrar y ascender.
(7) La auditoría guarda el sentido del concepto, no su texto (puede contener datos sensibles): `EVALUATION_ROUND_OPENED`,
`EVALUATOR_DECISION_RECORDED`, `EVALUATION_PERIOD_CHANGED`, `EVALUATION_ROUND_CLOSED`, `PLAYER_PROMOTED`.

**Pendiente.** Rúbrica versionada con criterios (P-06; hoy `criteria` es JSON libre), resumen de asistencia del periodo dentro de la ronda,
notificación a los evaluadores, cierre automático al vencer la ronda (hoy lo hace la coordinación), soportes del concepto y alcance de
evaluador y entrenador sobre los expedientes en `PlayerAccessPolicy`.

## Partidos y convocatorias (competitions)

Alcance ligero: torneo → partido → convocatoria → autorización del acudiente → resultado, sin generación de PDF ni calendario de torneos.

| Endpoint | Acceso | Notas |
|---|---|---|
| `POST /api/v1/tournaments` | `COORDINATOR` | Nombre único por temporada; categoría, escenario y reglamento opcionales (RF-031). |
| `GET /api/v1/tournaments?seasonId=` | coordinador, comité, entrenador | |
| `POST /api/v1/matches` | `COORDINATOR` | Crea el evento con las reglas de agenda (RN-14, 409 `SCHEDULE_CONFLICT`). Amistoso si no hay torneo; si lo hay, misma categoría y dentro de sus fechas. |
| `GET /api/v1/matches`, `GET /api/v1/matches/{id}` | coordinador y comité: todos; entrenador: los suyos | El detalle incluye la convocatoria con causa y requisitos (solo personal). |
| `POST /api/v1/matches/{id}/cancel` | `COORDINATOR` | Con motivo, auditado; avisa a los acudientes de los confirmados. `/events/{id}/cancel` rechaza partidos (409). |
| `POST /api/v1/matches/{id}/callup` | entrenador responsable o coordinador | Evalúa a cada jugador; el que no cumple queda `NOT_ELIGIBLE` con su causa y no se convoca. Repetir re-evalúa. |
| `DELETE /api/v1/matches/{id}/callup/{playerId}` | entrenador responsable o coordinador | Idempotente; avisa si estaba confirmado. |
| `POST /api/v1/matches/{id}/callup/confirm` | `COORDINATOR` | Vuelve a evaluar. Si alguno ya no cumple: 409 `CALLUP_NOT_ELIGIBLE` con `players:[{playerId, causes}]` y no se confirma nada. |
| `POST /api/v1/matches/{id}/players/{playerId}/authorization` | `GUARDIAN` vinculado | Solo con convocatoria confirmada (si no, 404). Idempotente (`recordedNow`). |
| `PUT /api/v1/matches/{id}/result` | entrenador responsable o coordinador | Desde el inicio del partido. Minutos y goles por convocado confirmado; goles individuales ≤ marcador. Corregir exige `reason`. |
| `GET /api/v1/players/{id}/matches` | acudiente (sus hijos), coordinador, comité | Historial de partidos con convocatoria confirmada (RF-037, parte de partidos). |

**Decisiones.** (1) La elegibilidad (RN-06, RN-07) se compone con fachadas de los módulos dueños de cada dato: `players.api.CategoryRoster`
(activo en la categoría), `documents.api.ParticipationClearance` (certificado médico vigente el día del partido y autorización de
participación vigente), `payments.api.AccountStanding` (mora) — `competitions` no lee esas tablas. (2) Mora: cargo vigente vencido
más allá de `canchatrack.payments.arrears-grace-days` (0 por defecto) con saldo pendiente; la política definitiva sigue en DP-05.
(3) En `callup_entries.eligibility` solo se guarda si cada requisito se cumple, nunca contenido de salud (RN-17); el acudiente no ve causas
ni requisitos. (4) El partido es un evento de `scheduling`: `scheduling.api.MatchEventScheduling` reutiliza la programación y la
cancelación (cruces, auditoría) cambiando solo los tipos admitidos; `competitions` lee el evento como proyección de solo lectura.
(5) Los avisos se encolan en `notifications` (tabla `notifications`, `PENDING`, deduplicados) en la misma transacción; el payload es solo de
plantilla. (6) Toda operación sobre un partido bloquea su fila (`FOR UPDATE`): proponer, confirmar y registrar el resultado se serializan.
(7) V15 es aditiva: `tournaments.category_id|venue_id|rules` y `callup_entries.goals`.
(8) Auditoría: `TOURNAMENT_CREATED`, `MATCH_SCHEDULED`, `CALLUP_PROPOSED`, `CALLUP_REMOVED`, `CALLUP_CONFIRMED`, `MATCH_AUTHORIZED`,
`MATCH_CANCELLED`, `MATCH_RESCHEDULED`, `MATCH_RESULT_RECORDED`, `MATCH_RESULT_CORRECTED` (más `EVENT_SCHEDULED`/`EVENT_RESCHEDULED`/`EVENT_CANCELLED` de agenda).
(9) Al registrar el partido, `scheduling.api.MatchEventScheduling.announce` avisa al entrenador con la cita en su calendario; las familias reciben el recordatorio
48 h y 2 h antes solo si su hijo está confirmado (ver «Avisos de agenda»).

**Pendiente.** Revocación de la autorización por el acudiente, historial deportivo con asistencia y evaluaciones publicadas (RF-037 completo, llega con
reportes), alcance de entrenador y evaluador en `PlayerAccessPolicy` (el entrenador aún no consulta el historial de un jugador) y la política de mora definitiva.

## Auditoría y reportes (audit, reports)

Alcance ligero y de solo lectura: consulta de la bitácora (CU-14, RF-051) y dos reportes de consulta (CU-12, RF-042). El informe PDF versionado por
jugador (CU-10, RF-049) y el cierre o reapertura (CU-11, RF-050, RN-18) se describen en la sección siguiente.

| Endpoint | Acceso | Notas |
|---|---|---|
| `GET /api/v1/audit/events` | `COORDINATOR` | Filtros `action`, `entityType`, `entityId`, `actorId`, `outcome`, `from` (inclusivo), `to` (exclusivo); `limit` 1..200 (50) y `cursor`. Más reciente primero. |
| `GET /api/v1/reports/receivables` | coordinador, comité | Cargos con saldo pendiente con jugador, acudiente principal, concepto, valor, pagado, saldo y días de mora; totales por categoría. Filtros `seasonId`, `categoryId`, `asOf`. |
| `GET /api/v1/reports/receivables/export` | coordinador, comité | Mismo contenido en CSV (UTF-8 con BOM, abre en Excel). Queda auditado (`REPORT_EXPORTED`). |
| `GET /api/v1/reports/season-summary?seasonId=` | coordinador, comité | Plantilla por categoría, eventos por tipo y estado, asistencia de sesiones cerradas, resultados de partidos y recaudo de la temporada. |

**Decisiones.** (1) La paginación de auditoría es por claves `(occurred_at, id)` con cursor opaco, no por número de página: las páginas son estables aunque entren
eventos nuevos y el costo no crece con la profundidad; V16 añade `ix_audit_events_occurred (occurred_at DESC, id DESC)`. (2) La primera página de cada búsqueda queda
auditada como `AUDIT_QUERIED` (con los filtros, sin el contenido devuelto; DDS 12.2 «consultas administrativas de alta sensibilidad»); pedir las siguientes con el cursor
no genera otro rastro. (3) La respuesta **no** incluye IP ni agente de usuario: quedan en la tabla para investigación, pero publicarlos es una política pendiente (DDS 12.3);
`before` y `after` ya son resúmenes seguros, sin datos de salud ni secretos. (4) Solo el coordinador consulta la bitácora; el DAD menciona un «administrador autorizado»
que el sistema aún no modela. (5) `reports` es un modelo de lectura: consulta con SQL de solo lectura tablas y vistas de otros módulos (`charge_balances`,
`category_assignments`, `sports_events`…) sin depender de sus clases, así que `ArchitectureTest` se mantiene; el costo es que un cambio de esas tablas obliga a revisar
`JdbcReportStore`. (6) Cartera = cargos `PENDING` o `PARTIAL` (los anulados y saldados no cuentan); los días de mora son los transcurridos desde el vencimiento hasta `asOf`
(hoy en la zona del club por defecto), sin días de gracia. Un jugador sin categoría vigente aparece en «Sin categoría». `lines` se corta en 5000 filas
(`truncated`), los totales por categoría siempre suman todo. (7) El porcentaje de asistencia usa la misma fórmula que el módulo de asistencia (se duplica en `reports.domain`
para no acoplar módulos). (8) CSV seguro: los textos que empiezan por `=`, `+`, `-` o `@` se prefijan con `'` porque el acudiente escribe los nombres (inyección de fórmulas);
respuesta con `Cache-Control: no-store`. (9) Las exportaciones que sustituyen las planillas (ERS 7.3) cubren hoy solo cartera; el CSV de plantilla, asistencia, convocatorias
y seguimiento de evaluación, y el Excel (.xlsx) y el PDF, quedan pendientes.

**Pendiente.** Exportaciones restantes de ERS 7.3, política de publicación de IP y agente de usuario, rol de administrador autorizado, retención y archivo de la bitácora, y el historial
deportivo completo por jugador (RF-037).

## Informe de temporada y cierre del expediente (reports, players, documents)

Informe PDF por jugador y temporada (CU-10, RF-049) y cierre o reapertura del expediente (CU-11, RF-050, RN-18, transiciones T-11 y T-12). V17 añade
`season_closures`; `season_reports` (consecutivo, versión, snapshot, huella) ya existía desde V11.

| Endpoint | Acceso | Notas |
|---|---|---|
| `POST /api/v1/players/{id}/season-reports` | `COORDINATOR` | Genera el PDF. Cuerpo `{seasonId, reason?}`; `reason` es obligatorio desde la segunda versión. 201. |
| `GET /api/v1/players/{id}/season-reports?seasonId=` | coordinador, comité | Versiones generadas, la más reciente primero. |
| `GET /api/v1/season-reports/{reportId}` | coordinador, comité | Metadatos. El PDF se descarga con `GET /api/v1/files/{fileId}`. |
| `GET /api/v1/season-reports/verify?code=` | coordinador, comité | Comprueba el código impreso; `current` indica si es la versión vigente. |
| `POST /api/v1/players/{id}/season-closure` | `COORDINATOR` | Cierra el expediente (ACTIVE → SEASON_CLOSED). Cuerpo `{seasonId, reportId}`. 201. |
| `POST /api/v1/players/{id}/season-closure/reopen` | `COORDINATOR` | Reabre (SEASON_CLOSED → ACTIVE). Cuerpo `{reason, detail, confirmation}`. |
| `GET /api/v1/players/{id}/season-closure` | coordinador, comité | Último cierre, vigente o ya reabierto. |

**Decisiones.** (1) *Contenido* (RF-049): asistencia (sesiones cerradas de eventos no cancelados, misma fórmula del módulo de asistencia), evaluaciones **publicadas**
(rondas cerradas, con el concepto de cada evaluador y el plan de seguimiento; sin las justificaciones, que pueden ser sensibles) y participación en partidos
(convocatoria confirmada, minutos y goles). No incluye documento de identidad ni datos de salud (RN-17). El periodo es el de la temporada; si aún no termina, el
informe cubre hasta hoy y lo declara «parcial». (2) *Identificación*: consecutivo `IT-000012` (secuencia que no se reutiliza, puede dejar huecos si una transacción se
revierte), código de verificación `XXXX-XXXX-XXXX` de 60 bits sin caracteres confundibles, y SHA-256 del archivo. Los datos usados se congelan en `season_reports.snapshot`.
(3) *Versionado*: regenerar no sobrescribe; la versión nueva apunta a la anterior y exige motivo (lo exige también la base). La generación por jugador y temporada se
serializa con un bloqueo consultivo. (4) *Generador de PDF propio* (`SimplePdf`, ~250 líneas, sin dependencias nuevas): A4, Helvetica, WinAnsi, paginación y pie
numerado, y determinista (mismo contenido, mismos bytes); si el club exige un diseño más rico, el puerto `SeasonReportRenderer` permite sustituirlo por OpenPDF o
similar sin tocar el resto. (5) *Archivo*: el PDF se guarda con la misma ruta de los demás archivos (`documents.api.FileArchive`), con el coordinador como autor;
`GET /files/{id}` lo entrega a quien lo subió, al coordinador y al comité. (6) *Cierre*: exige la **última** versión del informe y que el expediente deportivo no haya
cambiado después de generarlo (marcas o correcciones de asistencia, cierre de sesiones, rondas cerradas o resultados de partidos); si cambió, 409 y hay que generar
una versión nueva. El jugador debe estar `ACTIVE`: uno en evaluación o con un ascenso aprobado pendiente debe resolverlo antes. El cierre conserva la categoría y todo el
historial, y deja rastro en `season_closures`, `state_history` (T-11) y auditoría. (7) *Reapertura*: solo el coordinador, con motivo, detalle y **confirmación reforzada**
(escribir el código del jugador); marca el cierre como reabierto (quién, cuándo, por qué) sin borrar nada, deja T-12 en `state_history` y auditoría con el motivo. Se
reabre siempre a `ACTIVE`, que es el único estado desde el que se cierra. (8) *Solo lectura* (RN-18): el cierre saca al jugador de la plantilla y de los flujos deportivos
porque estos exigen el estado `ACTIVE` o `IN_EVALUATION` (asistencia, evaluaciones, convocatorias); **pagos y documentos siguen aceptando escrituras** de un expediente
cerrado (saldar una deuda, renovar un certificado) y no se bloquearon a propósito. (9) *Plantilla provisional* (`plantilla-1`): P-09 sigue sin respuesta del club; el
formato definitivo solo cambiaría el renderizador.

**Pendiente.** Verificación pública del código sin sesión (hoy exige rol; hace falta un límite de tasa antes de exponerla), acceso del acudiente a su informe, cierre de la
temporada como entidad (`seasons.status = CLOSED`, hoy no se toca) y cierre en lote, notificación al acudiente (RF-047 en lo que le corresponde), la plantilla y los campos
definitivos del informe (P-09), el Excel y PDF de las exportaciones de ERS 7.3, y una carrera residual: un cambio confirmado entre la lectura de datos del informe y su
inserción puede no detectarse como «posterior» (ventana de milisegundos; se cierra con aislamiento serializable si el club lo exige).

## Avisos de agenda (scheduling, notifications)

Al programar o cancelar un entrenamiento o una evaluación, y antes de que empiece, el club avisa por correo. Sin migración nueva: los avisos
usan la cola de `notifications` (V11) y el procesador ya existente.

| Aviso | Cuándo | Contenido |
|---|---|---|
| `EVENT_SCHEDULED` | al programar (`POST /events`) | Tipo de sesión, categoría, fecha y hora en la zona del club, escenario y dirección, entrenador, instrucciones; invitación `.ics` `METHOD:REQUEST`. |
| `EVENT_CANCELLED` | al cancelar (`POST /events/{id}/cancel`) | «CANCELACIÓN» destacada con el motivo; `.ics` `METHOD:CANCEL` con `SEQUENCE` = revisión del evento, para que el calendario retire la cita. |
| `EVENT_REMINDER` | proceso periódico, por defecto 24 h y 2 h antes | «Faltan N horas»; sin `.ics` (ya lo tienen). |

**Destinatarios.** Los acudientes con vínculo vigente de los jugadores en actividad de la categoría (`players.api.CategoryRoster` +
`identity.api.GuardianContacts`) y el entrenador titular del evento. Una acudiente de dos hijos en la misma categoría recibe **un** correo.

**Partidos.** Las familias se enteran de un partido por su convocatoria (`competitions`), así que aquí el **entrenador** es el único destinatario de
la programación, la cancelación y la reprogramación, con la cita en su calendario (`.ics`, mismo UID y `SEQUENCE` creciente) y el nombre del
rival en el asunto de la cita y en el cuerpo. El **recordatorio** de un partido sí va a los acudientes de los jugadores con convocatoria **confirmada** y al
entrenador, por defecto 48 h y 2 h antes (los propuestos o no elegibles aún no saben si juegan). `scheduling` no conoce convocatorias: pregunta a
`competitions` por el puerto `scheduling.api.MatchAudience`, que este módulo implementa.

**Recordatorios.** `EventReminderService.sendDue()` mira los eventos confirmados que empiezan dentro de la hora más lejana configurada y envía, a
cada uno, el recordatorio del tramo en que está. La clave de deduplicación incluye el tramo (`reminder:24`, `reminder:2`), así que el proceso
puede correr cuantas veces quiera sin repetir un correo. Un evento **programado dentro** de un tramo no recibe el recordatorio de ese tramo: el aviso
de programación ya le dio la fecha.

| Parámetro | Variable | Por defecto |
|---|---|---|
| `canchatrack.app-url` | `APP_URL` | vacío: el correo sale sin enlace |
| `canchatrack.scheduling.reminders.hours` | | `24,2` para entrenamientos y evaluaciones (1 a 168 horas, hasta 5; un valor inválido impide el arranque y lista todos los problemas) |
| `canchatrack.scheduling.reminders.match-hours` | | `48,2` para partidos (mismas reglas) |
| `canchatrack.scheduling.reminders.interval` | | `PT5M` |
| `canchatrack.scheduling.reminders.enabled` | | `true` (apagado en el perfil de pruebas) |

**Decisiones.** (1) El `.ics` es un adjunto de calendario del propio aviso, no un «documento adjunto»: RN-17 (nada de documentos ni datos de salud en
los correos) sigue en pie. (2) Los avisos **no nombran jugadores**: cada acudiente vería los hijos de otras familias; se identifica la categoría. (3) Se
encolan en la transacción de la programación: si esta falla, no sale ningún correo; un fallo de correo no deshace la programación (el procesador
reintenta). (4) Sin acudientes ni entrenador no se encola nada. (5) Un aviso sin fecha, hora o identificador completos sale sin `.ics` en vez de fallar.

**Pendiente.** Alertar a la coordinación cuando un aviso queda `FAILED`, y confirmar con el club el uso del `.ics`, la dirección del enlace y la anticipación de 48 h y 2 h de los partidos.

## Reprogramación (scheduling, competitions, notifications)

Un evento o un partido **confirmado y que aún no empieza** puede moverse sin cancelarlo y crear otro. Sin migración nueva: la revisión del evento es
`sports_events.optimistic_version`, que ya subía al cancelar o completar.

| Operación | Ruta | Quién |
|---|---|---|
| Reprogramar un entrenamiento o una evaluación | `POST /events/{eventId}/reschedule` | COORDINATOR |
| Reprogramar un partido | `POST /matches/{matchId}/reschedule` | COORDINATOR |

El cuerpo lleva `startAt`, `endAt` y `reason` (obligatorios) y, opcionales, `venueId`, `headCoachId`, `meetingPoint` e `instructions`: lo que no se indica
**se conserva**. `meetingAt` sí se reemplaza siempre (es una hora de concentración atada a la fecha; si se omite se borra). Un partido sale por su propio recurso
porque además de avisar hay que revisar la convocatoria; pedirlo por `/events/...` responde 409 y señala la ruta correcta.

**Reglas.** (1) Solo `CONFIRMED` y con inicio futuro, si no 409 `RESOURCE_STATE_CONFLICT`. (2) Mismas validaciones que al programar (fechas, duración,
escenario activo, entrenador con rol COACH) más el motivo, todas acumuladas en un 422. (3) Un cambio que no modifica nada es 422 (`startAt`).
(4) El cruce se calcula **excluyendo al propio evento**: correr una sesión media hora no choca con su horario anterior; contra cualquier otro evento
sigue siendo 409 `SCHEDULE_CONFLICT` con la lista. (5) Los cupos de escenario, entrenador y categoría se bloquean como al programar. (6) Un partido además debe
caber en las fechas del torneo.

**Avisos.**

| Aviso | Cuándo | Contenido |
|---|---|---|
| `EVENT_RESCHEDULED` | al reprogramar un entrenamiento o evaluación | «REPROGRAMADO», motivo, fecha anterior y nueva programación; `.ics` `METHOD:REQUEST` con el **mismo UID** y `SEQUENCE` = revisión, que el calendario toma como actualización de la cita. Va a los acudientes, al entrenador nuevo y al **anterior** si cambió. |
| `EVENT_CANCELLED` | al cancelar | Su `.ics` ahora lleva `SEQUENCE` = revisión (antes fijo en 1), así un evento reprogramado y luego cancelado sí se retira. |
| `MATCH_RESCHEDULED` | al reprogramar un partido | A los acudientes de los jugadores **confirmados**: fecha anterior y nueva, punto de encuentro y motivo. Sin `.ics` ni datos de salud. |

Cada revisión tiene su propia clave de deduplicación (`rescheduled:<n>`), de modo que dos cambios seguidos avisan dos veces y un reintento no duplica.
Los recordatorios toman como referencia la **última modificación** del evento (`updated_at`, no `created_at`) y desde la revisión 1 su clave incluye
`:r<n>`: tras reprogramar vuelven a salir los de la nueva fecha, y un evento movido dentro de un tramo no recibe un recordatorio prematuro.

**Convocatoria de un partido.** Al reprogramar se reevalúan los jugadores `PROPOSED` y `NOT_ELIGIBLE` con la nueva fecha (por ejemplo, un certificado médico
que vence antes). Los `CONFIRMED` **no se descartan solos**: se conservan y la respuesta los lista en `confirmedToReview` con la causa (`playerId` y
`causes`), para que el coordinador decida. La respuesta es `{match, confirmedToReview}`.

**Decisiones.** (1) No se reabre la confirmación para no quitar a un jugador sin que nadie lo decida; se informa. (2) Auditoría: `EVENT_RESCHEDULED` (antes,
después y motivo) y, en un partido, `MATCH_RESCHEDULED` con los conteos de avisados, reevaluados y por revisar. (3) El aviso al entrenador anterior es un correo
más; su calendario conserva la cita vieja hasta que la retire a mano (no se emite una cancelación por separado). (4) El motivo viaja en el correo a las familias:
no debe incluir datos de salud (RN-17).

**Pendiente.** Retirar del calendario del entrenador anterior con una cancelación propia y reprogramación masiva de una jornada.

## Procesador de notificaciones (notifications)

Los módulos encolan avisos con `notifications.api.NotificationQueue` (fila `PENDING` en la misma transacción del negocio, deduplicada). El
procesador es el otro extremo de esa cola: sin nueva migración, usa `notifications` y `delivery_attempts` (V11).

| Pieza | Qué hace |
|---|---|
| `NotificationProcessor` (`application`) | Cada pasada reclama avisos vencidos, los renderiza, los entrega con el puerto `EmailSender` y registra el resultado. No es transaccional: el envío no mantiene abierta una transacción. |
| `NotificationOutbox` / `JdbcNotificationOutbox` | `claimDue` toma avisos con `FOR UPDATE SKIP LOCKED` y los **arrienda** moviendo `next_attempt_at` (5 min por defecto): dos instancias nunca toman el mismo aviso y el envío ocurre fuera de la transacción. `markSent` y `markFailed` registran el intento en `delivery_attempts` solo si el aviso sigue pendiente y no tiene ya ese intento, así un proceso rezagado no pisa el resultado de otro. |
| `RetryPolicy` (`domain`) | Espera exponencial (x4): 1 min, 4, 16, 64 y un máximo de 5 intentos; tope de 6 h. Al agotarse, el aviso queda `FAILED`. Un fallo definitivo del proveedor (dirección inválida) no se reintenta. |
| `EmailTemplates` (`domain`) | Texto plano en español para `MATCH_CALLUP`, `MATCH_CALLUP_REMOVED`, `MATCH_CANCELLED`, `DOCUMENT_EXPIRY`, `EVENT_SCHEDULED`, `EVENT_CANCELLED` y `EVENT_REMINDER`; fechas en la zona del club; los datos se reducen a una línea para impedir inyección de encabezados. Un tipo sin plantilla es un fallo definitivo (`NO_TEMPLATE`), no se envía nada improvisado. |
| `LoggingEmailSender` | Valor por defecto: **no envía**; deja en el log el asunto y el destinatario enmascarado (el cuerpo solo en `DEBUG`). Solo para desarrollo y pruebas. |
| `SmtpEmailSender` | `canchatrack.notifications.sender=smtp` con `spring.mail.*`. Es el valor por defecto del perfil `prod`. Variables: `MAIL_HOST`, `MAIL_FROM` (obligatorias) y `MAIL_PORT`, `MAIL_USER`, `MAIL_PASSWORD`, `MAIL_AUTH`, `MAIL_STARTTLS` (opcionales). |
| `NotificationScheduler` | `@Scheduled` con espera fija (`canchatrack.notifications.processor.interval`, 10 s). Apagado en el perfil de pruebas (`processor.enabled=false`); las pruebas invocan al procesador a mano. |

Decisiones: (1) **Entrega al menos una vez**: si el proceso muere entre el envío y el registro, el arriendo vence y el aviso se reintenta; un correo
repetido es preferible a uno perdido. (2) Las cuentas `GUARDIAN` reciben el aviso en el correo de su cuenta; el contenido es el de la plantilla
(RN-17: nunca datos de salud ni adjuntos). (3) Lo que se guarda en `last_error` y `provider_response` son clases de error y referencias, no direcciones ni
cuerpos. (4) Parámetros: `canchatrack.notifications.{sender, from, processor.{enabled, interval, batch-size, lease}, retry.{max-attempts, base-delay, max-delay}}`.

**Pendiente.** Verificar contra un servidor SMTP real (solo se comprobó el tipado), avisos de otros módulos (decisión del comité, convocatoria publicada, mora,
cierre de temporada), consulta y reintento manual de avisos `FAILED`, limpieza de avisos antiguos y una plantilla HTML con la identidad del club. Los recordatorios y
el `.ics` de la agenda ya están (ver «Avisos de agenda»).

## Contenedor e integración continua

- **`Dockerfile`** (multi-etapa): compila con Maven 3.9 y Temurin 21, extrae las capas de Spring Boot y ejecuta sobre `eclipse-temurin:21-jre-alpine` con un
  usuario sin privilegios. El perfil por defecto es **`prod`**: sin `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `MAIL_HOST` y `MAIL_FROM` no arranca, a propósito.
  Los archivos se guardan en el volumen `/data/files`. `docker build -t canchatrack-backend backend`.
- **`docker-compose.yml`**: `docker compose up -d` levanta solo PostgreSQL (puerto configurable con `DB_PORT`, p. ej. `$env:DB_PORT=5433`);
  `docker compose --profile app up --build` levanta además el backend en su imagen con el perfil `dev` (credenciales desechables, solo para la máquina local).
- **`.github/workflows/backend-ci.yml`** corre en cada PR y en cada push a `main` que toque `backend/`: (1) `mvn verify` con toda la suite, incluidas las
  pruebas de integración con PostgreSQL 16 real; si falla, sube los informes de Surefire y Failsafe como artefacto; (2) construye la imagen y la arranca contra
  PostgreSQL con el perfil `dev`: exige health `UP`, que el coordinador de desarrollo pueda iniciar sesión (las migraciones corrieron) y 401 sin token.
  El front (Figma Make) no dispara el pipeline. Una ejecución nueva cancela la anterior sobre la misma rama.
- **Nota Docker Engine 29:** exige API >= 1.40 y el docker-java de Testcontainers 1.21.x pide 1.32. El pipeline escribe `~/.docker-java.properties` con
  `api.version=1.44` (el mismo arreglo de las máquinas de desarrollo). Cuando Spring Boot gestione una versión de Testcontainers que lo corrija, se puede retirar.

**Pendiente.** Puertas de calidad adicionales (cobertura con JaCoCo y umbral, análisis estático, escaneo de dependencias e imagen), publicación de la imagen en un
registro, despliegue, y que `main` exija el pipeline en verde (protección de rama, se configura en GitHub, no en el código).

## Siguiente paso

Siguiente incremento: avisos de mora y de resultado del comité, sesiones recurrentes,
exportaciones, seguridad (recuperación de contraseña, verificación de correo, límite de tasa) y puertas de calidad. CORS ya está resuelto. CU-10 y CU-11 quedan con plantilla provisional hasta que el club defina P-09.
