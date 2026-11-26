-- Users and roles
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(120) NOT NULL,
    email         VARCHAR(160) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('USER', 'MALL_ADMIN', 'SUPER_ADMIN')),
    mall_id       BIGINT,  -- set for MALL_ADMIN: the mall they manage
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

-- Catalog
CREATE TABLE malls (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(160) NOT NULL,
    address     VARCHAR(300),
    area        VARCHAR(100),
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    open_time   TIME,
    close_time  TIME,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);
ALTER TABLE users ADD CONSTRAINT fk_users_mall FOREIGN KEY (mall_id) REFERENCES malls(id);

CREATE TABLE floors (
    id       BIGSERIAL PRIMARY KEY,
    mall_id  BIGINT NOT NULL REFERENCES malls(id) ON DELETE CASCADE,
    level    INT NOT NULL,
    label    VARCHAR(40),
    UNIQUE (mall_id, level)
);

CREATE TABLE categories (
    id    BIGSERIAL PRIMARY KEY,
    name  VARCHAR(80) NOT NULL UNIQUE
);

CREATE TABLE stores (
    id           BIGSERIAL PRIMARY KEY,
    mall_id      BIGINT NOT NULL REFERENCES malls(id) ON DELETE CASCADE,
    floor_id     BIGINT REFERENCES floors(id),
    category_id  BIGINT REFERENCES categories(id),
    name         VARCHAR(160) NOT NULL,
    unit_no      VARCHAR(30),
    contact      VARCHAR(40),
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_stores_mall ON stores(mall_id);
CREATE INDEX idx_stores_name ON stores(lower(name));

-- Offers
CREATE TABLE offers (
    id             BIGSERIAL PRIMARY KEY,
    store_id       BIGINT NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    title          VARCHAR(200) NOT NULL,
    description    TEXT,
    discount_type  VARCHAR(20),   -- PERCENT, FLAT, BOGO
    discount_value NUMERIC(10,2),
    valid_from     DATE NOT NULL,
    valid_to       DATE NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    created_at     TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_offers_store_status ON offers(store_id, status);

-- Parking
CREATE TABLE parking_zones (
    id           BIGSERIAL PRIMARY KEY,
    mall_id      BIGINT NOT NULL REFERENCES malls(id) ON DELETE CASCADE,
    name         VARCHAR(60) NOT NULL,   -- e.g. "Basement 1"
    total_slots  INT NOT NULL CHECK (total_slots > 0)
);

CREATE TABLE parking_status (
    zone_id          BIGINT PRIMARY KEY REFERENCES parking_zones(id) ON DELETE CASCADE,
    available_slots  INT NOT NULL CHECK (available_slots >= 0),
    updated_at       TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE parking_bookings (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id),
    zone_id     BIGINT NOT NULL REFERENCES parking_zones(id),
    slot_no     INT NOT NULL,
    start_time  TIMESTAMP NOT NULL,
    end_time    TIMESTAMP NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    CHECK (end_time > start_time)
);
-- Safety net against double booking of the same slot at the DB level
CREATE UNIQUE INDEX uq_active_slot ON parking_bookings(zone_id, slot_no)
    WHERE status = 'ACTIVE';

-- Loyalty
CREATE TABLE bills (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id),
    store_id   BIGINT NOT NULL REFERENCES stores(id),
    bill_no    VARCHAR(60) NOT NULL,
    amount     NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    image_url  VARCHAR(400),
    status     VARCHAR(20) NOT NULL DEFAULT 'PENDING'
               CHECK (status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (store_id, bill_no)   -- same bill can't be claimed twice
);

-- Append-only ledger: never UPDATE or DELETE rows here
CREATE TABLE points_ledger (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT NOT NULL REFERENCES users(id),
    bill_id          BIGINT REFERENCES bills(id),
    points           INT NOT NULL,                -- positive = earned, negative = redeemed
    type             VARCHAR(20) NOT NULL CHECK (type IN ('EARN', 'REDEEM', 'ADJUST')),
    idempotency_key  VARCHAR(100) NOT NULL UNIQUE,
    created_at       TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_user ON points_ledger(user_id);

-- Notifications
CREATE TABLE notifications (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT REFERENCES users(id),
    type       VARCHAR(40) NOT NULL,
    payload    TEXT,
    sent_at    TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

-- Footfall (analytics, fed by the simulator later)
CREATE TABLE footfall_events (
    id         BIGSERIAL PRIMARY KEY,
    mall_id    BIGINT NOT NULL REFERENCES malls(id) ON DELETE CASCADE,
    ts         TIMESTAMP NOT NULL,
    count      INT NOT NULL
);
CREATE INDEX idx_footfall_mall_ts ON footfall_events(mall_id, ts);
