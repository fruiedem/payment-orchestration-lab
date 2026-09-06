CREATE SCHEMA IF NOT EXISTS payment_orchestration;

COMMENT ON SCHEMA payment_orchestration IS
    'Application-owned schema; business tables are intentionally absent in bootstrap phase.';
