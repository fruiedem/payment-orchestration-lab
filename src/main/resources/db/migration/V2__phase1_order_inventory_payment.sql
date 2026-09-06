CREATE TABLE payment_orchestration.products (
    id BIGSERIAL PRIMARY KEY,
    sku VARCHAR(100) NOT NULL UNIQUE,
    stock INTEGER NOT NULL,
    CONSTRAINT products_stock_non_negative CHECK (stock >= 0)
);

CREATE TABLE payment_orchestration.orders (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES payment_orchestration.products(id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    amount BIGINT NOT NULL CHECK (amount > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'PAID')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE payment_orchestration.payments (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES payment_orchestration.orders(id),
    pg_transaction_id VARCHAR(100),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'SUCCESS', 'UNKNOWN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX one_successful_payment_per_order
    ON payment_orchestration.payments(order_id)
    WHERE status = 'SUCCESS';

COMMENT ON TABLE payment_orchestration.orders IS
    'Phase 1: deliberately processed in the same transaction as inventory and the external PG call.';
