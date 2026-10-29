<!--
Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
SPDX-License-Identifier: AGPL-3.0-only
This file is part of Kumbuka and is licensed under the GNU Affero
General Public License v3.0 only. See the LICENSE file in the
repository root for the full licence text.
-->

# kumbuka-worklist

The worklist service.

A scope states what it intends to do, as a list of items that are worked
through a process. An item is created, characterised, planned, claimed, worked
and terminated; that sequence takes weeks or months and every step changes the
item.

The service does not know what an item is *about*. In one estate an item is a
task; in another it is an application, a defect, a fruit tree, an insurance
case. The service fixes the **form** of a list and the meaning of a small
number of questions about it. Everything else is the scope's.

It is the sibling of the dispatch service and the opposite of it in one
property: **a worklist item is mutable for its whole life, while a dispatch
object freezes when it is sent.**

## What is here today

The **substrate**: the schema, its own database role, its own migration
sequence, row-level security on the tenancy axis, the binding to the tenant
realm, the read of the platform's scope directory, and the operator boundary.

The **domain** stands on that substrate: the declared vocabulary of
selectors, customer-defined attributes, items with typed relations between
them, milestones, workstreams and iterations, the claim lease and the number
space.

The **caller surface** carries the verbs of the worklist scheme -- `create`,
`read`, `update`, `query`, `accept`, `withdraw`, `close`, `advance`, `plan`,
`unplan`, `claim`, `claim_next`, `release`, `relate`, `unrelate` and
`validate` -- over REST under `/api/{scope}` and over MCP at `/mcp`, both
through the one verb surface. Beside them stand the service's declaration at
`GET /api/declaration`, `GET /api/whoami` and the health endpoint.

## How isolation is built

Five mechanisms, each of which can be removed independently and each of which
has a probe that watches it fail.

**Two enforcement layers.** The ORM filters every statement it builds on the
tenant. PostgreSQL's row-level security filters everything else — raw SQL,
native queries, any path around the ORM. Either layer alone would carry the
guarantee; both together are the seam. `FailClosedProbeIT` observes each layer
working with the other one switched off, so neither can be quietly inert.

**The policy fails closed.** The predicate compares `tenant_id` against a
session setting read with `current_setting('app.tenant_id', true)`. When
nothing bound it, the comparison is against NULL and matches no row. A
transaction that forgot to bind a tenant therefore sees nothing rather than
everything, and a write it attempts is refused rather than silently dropped.

**`FORCE`, not just `ENABLE`.** `ENABLE ROW LEVEL SECURITY` exempts the
table's owner. Here the owner is the *migrator*, which is the role every
migration carrying DML runs as — so without `FORCE` a backfill would read and
write across every tenant in the table and report success.
`RowLevelSecurityProbeIT` removes `FORCE`, watches the owner walk past the
policy, and puts it back.

**The runtime role's privileges are enumerated, not implied.** See below; this
is the one place the service departs from the substrate template it is
otherwise a copy of.

**The operator boundary is a missing GRANT.** No privilege exists that would
let the provider's role read an item. It is not a rule in application code and
not a policy: the enforcing artifact is a line that does not exist.
`MissingGrantProbeIT` observes the refusal, then grants the privilege, watches
the access succeed, and revokes it — because an absence that was never seen to
matter is not a boundary. The role it uses carries `BYPASSRLS` on purpose, so
the refusal cannot be attributed to row-level security.

## Roles, and why the runtime role owns nothing

| Role | Holds | Why |
| --- | --- | --- |
| `kumbuka_worklist` | `USAGE` on the schema; `SELECT, INSERT, UPDATE` on each domain table, named one at a time; `SELECT` on one platform view | The service connects as this and nothing else. Neither superuser nor `BYPASSRLS` — either would make every policy in the schema inert. It **owns nothing anywhere.** |
| the migrating role | `CREATEROLE`, `CREATE` on the database, and ownership of the schema and everything in it | Creating the service role is the one privileged act the migration set performs. Superuser would additionally confer `BYPASSRLS` and make a migration's own DML untestable. |
| the provider role | nothing here | The operator boundary. |

The sibling service arranges the runtime role's access by **ownership**: its
migration issues no table grant at all, and a Flyway callback hands the schema
and every relation in it to the runtime role, on the reasoning that an owner
needs no GRANT and a grant list is a thing that drifts.

What that arrangement also does, by PostgreSQL's rules rather than by
anyone's intent, is hand the runtime role the full privilege set — `DELETE`,
`INSERT`, `REFERENCES`, `SELECT`, `TRIGGER`, `TRUNCATE`, `UPDATE` — on every
relation it owns, implicitly, with no grant anywhere to show for it. A sweep
over a schema includes the Flyway history table, because that table lives in
the schema too.

**`TRUNCATE` is why that matters.** It bypasses row-level security completely,
independently of every policy and of whether `app.tenant_id` is bound. A
runtime role holding it can empty a tenant-scoped table across the tenant
boundary, and no part of the isolation apparatus sees it happen.

So this service enumerates instead. The cost is real and is accepted: a table
added by a later migration receives no privilege automatically, and a
migration that forgets its grant produces a service that cannot read its own
new table. That failure is loud and immediate; the one it replaces is silent
and permanent. The drift the ownership arrangement was guarding against is
answered by `ServiceRolePrivilegeIT`, which reads the catalog rather than a
maintained list and requires, per table and per privilege:

- exactly `SELECT`, `INSERT`, `UPDATE` on every domain table — no `DELETE`
  until a verb genuinely deletes, and never `TRUNCATE`, `TRIGGER` or
  `REFERENCES`;
- nothing whatever on `flyway_schema_history`, which belongs to the migrator;
- and that the runtime role owns nothing, so the privileges above are grants
  rather than an owner's implicit set.

`ServiceRoleConformanceIT` then asks the whole catalog whether the role holds
anything outside its own schema — so it also covers the neighbouring service
that does not exist yet.

## Running it

Configuration is environment variables with a `WORKLIST_` prefix; see
`backend/src/main/resources/application.properties` for the full set and its
development defaults. There is no deployment hostname in this repository and
no credential beyond placeholders: the development defaults of the two
passwords in `application.properties`, and the password `V2__service_role.sql`
gives a role it creates, which a deployment beyond a development machine
replaces from its own secret store.

```
WORKLIST_DB_JDBC_URL        jdbc:postgresql://<host>:5432/<database>
WORKLIST_DB_USERNAME        the service role         (default kumbuka_worklist)
WORKLIST_DB_PASSWORD        its password
WORKLIST_MIGRATOR_USERNAME  the migrating role
WORKLIST_MIGRATOR_PASSWORD  its password
WORKLIST_OIDC_ISSUER        the tenant realm's issuer URL
WORKLIST_OIDC_AUDIENCE      the platform's audience  (default kumbuka-worklist);
                            set and empty, the service refuses to start
WORKLIST_TENANT_ID          the tenancy axis for this deployment — MANDATORY,
                            no default, the service refuses to start without it
```

**`WORKLIST_TENANT_ID` has no default and the service will not guess one.** It
is the one value in this configuration that no later layer can check: the ORM
filter, the session binding and the row-level-security policy all enforce the
axis they are handed, so a guessed tenant id is enforced perfectly, on somebody
else's data. An unset variable and an empty one are both refused at start-up,
with a message naming the variable — an empty one is refused explicitly because
`docker compose config` substitutes the empty string for a variable that is not
set, and an expression default does not apply to it.

**`WORKLIST_OIDC_AUDIENCE` set and empty is refused too.** Its default applies
only where the variable is absent; the empty string `docker compose` hands
over for a variable the shell lacks resolves to an empty audience, and an
empty audience validates none, so the start-up check refuses it.

**The service role's password must be rotated.** `V2__service_role.sql`
creates the role with a placeholder so that a cold start against an empty
database needs no manual step. Any deployment reachable from outside a
development machine replaces it with `ALTER ROLE kumbuka_worklist PASSWORD …`
from its own secret store.

**The migrating role must exist and must not be a superuser.** It needs
`CREATEROLE` and `CREATE` on the database, and nothing more.

### Logging

One line per completed write at INFO, naming the verb and the address; every
refusal at DEBUG, by its code; ERROR only for an unexpected failure, with the
reference the caller is given. No line carries what a caller sent. Every line
carries the call's request id, taken from `Kumbuka-Request-Id` where it is a
UUID, minted otherwise, and returned in the same header.

### Build and test

```
cd backend
mvn verify          # unit tests and the full integration suite
docker build -t kumbuka-worklist .
```

The integration suite starts PostgreSQL and Keycloak containers of its own
through Testcontainers, so Docker must be available. It is **not** behind a
profile: every guarantee this service makes is a statement about a running
database or a running identity provider, and a gate that has to be switched on
is one that will be found switched off.

The Keycloak probe binds host port **38280** — fixed, because the issuer URL
must be known at build time, and different from the sibling service's 38180 so
the two suites can run side by side.

## Licence

AGPL-3.0-only. See `LICENSE`.
