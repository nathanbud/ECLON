# PostgreSQL naming conventions

These apply to the `core` schema (the new design). The `legacy` schema keeps the old tables as they were, with names lowercased and nothing else changed.

| Thing | Rule | Example |
|---|---|---|
| Schemas | lowercase, one per purpose | `legacy`, `core` |
| Tables | `snake_case`, singular noun | `member`, `loan_application` |
| Columns | `snake_case`, no abbreviations unless universal | `branch_id`, `created_at` |
| Primary key | `id`, `bigint generated always as identity` | `member.id` |
| Foreign key column | `<referenced_table>_id` (role prefix when needed) | `member_id`, `approved_by_user_id` |
| Natural/business keys | separate column with a unique constraint, never the PK | `member_no`, `username` |
| Booleans | `is_` / `has_` prefix, `NOT NULL` with a default | `is_enabled` |
| Timestamps | `timestamptz`, suffix `_at` | `created_at`, `posted_at` |
| Dates | `date`, suffix `_date` | `due_date` |
| Money | `numeric(19,4)`, suffix `_amount` | `principal_amount` |
| Rates | `numeric(9,6)`, suffix `_rate` | `interest_rate` |
| Status/type values | lookup table or `text` with a `CHECK`, values `UPPER_SNAKE` | `PENDING`, `FOR_REVIEW` |
| Primary key constraint | `pk_<table>` | `pk_member` |
| Foreign key constraint | `fk_<table>_<column>` | `fk_user_role_user_id` |
| Unique constraint | `uq_<table>_<columns>` | `uq_app_user_username` |
| Check constraint | `ck_<table>_<rule>` | `ck_audit_log_action` |
| Index | `ix_<table>_<columns>` | `ix_audit_log_entity` |
| Migration files | `V<nnn>__<what_it_does>.sql`, never edited once applied | `V001__schemas.sql` |

Other rules:
- Every table has `created_at`, and mutable ones also `updated_at`.
- Posted financial rows are never updated or deleted. Corrections are reversal rows that point at the original.
- Reserved words are not used as names.
- Every foreign key column gets an index.
- Each table and column has a `COMMENT ON` describing it.
