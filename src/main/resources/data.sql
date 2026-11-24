-- Catalogo de dominio (SPEC secao 5).
-- ON CONFLICT DO NOTHING: roda a cada startup sem duplicar e sem sobrescrever ajustes manuais.
INSERT INTO project_catalog (repository, responsible, triage_guidance) VALUES
    -- Repositorios ficticios do desafio
    ('equipe/projeto-teste', 'Time Billing (billing-team)',
     'Erros 5xx: pedir log completo e passos de reproducao. Timeouts: verificar dependencia externa citada na descricao, nunca presumir qual.'),
    ('equipe/projeto-mobile', 'Time Mobile (mobile-team)',
     'Bugs de UI: pedir versao do app e modelo do device. Sugestoes de UX entram como melhoria.'),
    ('equipe/projeto-docs', 'Time Docs (docs-team)',
     'A maioria das issues aqui e duvida ou melhoria de conteudo; raramente bug.'),
    -- Repositorio de teste REAL (conta bot): onde os comentarios sao publicados de verdade
    ('leandro-triage-bot/projeto_teste', 'Time Billing (billing-team)',
     'Erros 5xx: pedir log completo e passos de reproducao. Timeouts: verificar dependencia externa citada na descricao, nunca presumir qual.')
ON CONFLICT (repository) DO NOTHING;
