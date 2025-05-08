CREATE TABLE IF NOT EXISTS payments (
                                        msg_id        TEXT PRIMARY KEY,
                                        debit_acct    TEXT NOT NULL,
                                        credit_acct   TEXT NOT NULL,
                                        amount        DOUBLE PRECISION NOT NULL,
                                        transaction_ts BIGINT NOT NULL,
                                        processed_at  TIMESTAMP DEFAULT now()
    );

CREATE TABLE IF NOT EXISTS fraud_alerts (
                                            msg_id        TEXT PRIMARY KEY REFERENCES payments(msg_id),
    amount        DOUBLE PRECISION NOT NULL,
    alert_ts      TIMESTAMP DEFAULT now()
    );
