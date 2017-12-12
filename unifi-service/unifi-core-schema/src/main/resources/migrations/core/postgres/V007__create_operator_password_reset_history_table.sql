CREATE TABLE core.operator_password_reset_history(
  client_id           CITEXT NOT NULL,
  username            CITEXT NOT NULL,
  token_hash          BYTEA NOT NULL,
  algorithm           VARCHAR(12) NOT NULL,
  expiry_date         TIMESTAMP NOT NULL,
  deletion_reason     VARCHAR(12) NOT NULL,
  since               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY (client_id, username, since),
  CONSTRAINT fk_operator_password_reset_to_operator
    FOREIGN KEY (client_id, username)
    REFERENCES core.operator,
  CHECK (LENGTH(token_hash) = 56),
  CHECK (deletion_reason IN ('used', 'expired', 'cancelled'))
);
