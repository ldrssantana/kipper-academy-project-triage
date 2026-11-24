-- Catalogo de dominio (dados ficticios, SPEC secao 5).
-- ON CONFLICT DO NOTHING: roda a cada startup sem duplicar e sem sobrescrever ajustes manuais.
INSERT INTO project_catalog (repository, responsible, triage_guidance) VALUES
    ('equipe/projeto-teste', 'Time Billing (billing-team)',
     'Erros 5xx: pedir log completo e passos de reproducao. Timeouts: verificar dependencia externa citada na descricao, nunca presumir qual.'),
    ('equipe/projeto-mobile', 'Time Mobile (mobile-team)',
     'Bugs de UI: pedir versao do app e modelo do device. Sugestoes de UX entram como melhoria.'),
    ('equipe/projeto-docs', 'Time Docs (docs-team)',
     'A maioria das issues aqui e duvida ou melhoria de conteudo; raramente bug.')
ON CONFLICT (repository) DO NOTHING;
