INSERT INTO compute_pairing_identity (id, public_key, private_key_encrypted) SELECT 1, ?, ? WHERE NOT EXISTS (SELECT 1 FROM compute_pairing_identity WHERE id = 1)
