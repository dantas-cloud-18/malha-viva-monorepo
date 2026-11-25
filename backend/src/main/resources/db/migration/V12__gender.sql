-- Genero autodeclarado e OPCIONAL, so para estatistica agregada e anonima (buckets < 5 suprimidos).
-- NULL = nao informado (diferente de 'prefer_not_to_say'). Plano de identidade: nunca cruza com telemetry_points.
ALTER TABLE accounts ADD COLUMN gender TEXT
    CHECK (gender IN ('male', 'female', 'non_binary', 'prefer_not_to_say'));
