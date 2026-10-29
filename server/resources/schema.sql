CREATE TABLE IF NOT EXISTS accounts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email text NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Magic-link tokens: single use, short lived. Only hashes are stored.
CREATE TABLE IF NOT EXISTS login_tokens (
  token_hash bytea PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  expires_at timestamptz NOT NULL,
  used_at timestamptz
);

-- Desktop sign-in: the app holds a secret poll id (only its hash is stored) and claims a session
-- once the link was confirmed in the browser.
ALTER TABLE login_tokens ADD COLUMN IF NOT EXISTS poll_hash bytea UNIQUE;
ALTER TABLE login_tokens ADD COLUMN IF NOT EXISTS poll_claimed_at timestamptz;

CREATE TABLE IF NOT EXISTS sessions (
  token_hash bytea PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  created_at timestamptz NOT NULL DEFAULT now()
);

-- One row per marked recording. id is the work-id hidden in the audio.
CREATE TABLE IF NOT EXISTS works (
  id bigint PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  engine text NOT NULL,
  payload_hex text NOT NULL UNIQUE,
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'final')),
  fingerprint integer[],
  audio_sha256 text,
  created_at timestamptz NOT NULL DEFAULT now(),
  finalized_at timestamptz
);

-- Public record, versioned. Each version's canonical JSON is timestamped with OpenTimestamps.
CREATE TABLE IF NOT EXISTS work_versions (
  work_id bigint NOT NULL REFERENCES works(id),
  version integer NOT NULL,
  record jsonb NOT NULL,
  record_canonical bytea NOT NULL,
  record_sha256 text NOT NULL,
  ots bytea,
  ots_status text NOT NULL DEFAULT 'none' CHECK (ots_status IN ('none', 'pending', 'complete', 'failed')),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (work_id, version)
);
