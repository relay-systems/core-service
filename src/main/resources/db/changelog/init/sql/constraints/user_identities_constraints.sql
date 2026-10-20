ALTER TABLE user_identities ADD CONSTRAINT user_identities_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE user_identities ADD CONSTRAINT uq_user_identities_issuer_subject UNIQUE (issuer, subject);
ALTER TABLE user_identities ADD CONSTRAINT user_identities_issuer_check CHECK (issuer <> '');
ALTER TABLE user_identities ADD CONSTRAINT user_identities_subject_check CHECK (subject <> '');
