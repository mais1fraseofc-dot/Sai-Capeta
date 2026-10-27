# Corvo Navegador — canal de atualizações beta

Aplicativo Android independente em `corvo-browser/`, pacote `br.com.corvo.browser`.

- Versões publicadas: `corvo-publish/Corvo-Navegador.apk`
- Manifesto de versão: `corvo-publish/latest.json`
- Ao abrir e desbloquear, o navegador consulta atualizações no máximo uma vez por dia.
- No menu existe `Verificar atualizações`.
- O aplicativo baixa o APK, compara SHA-256, e encaminha ao instalador do Android. A instalação exige aprovação do sistema.
- Para atualizar **sem desinstalar e sem perder dados**, as próximas versões precisam manter a mesma assinatura e usar `versionCode` maior.
- A chave de assinatura beta fica no cache privado da execução do GitHub Actions. Esse cache pode ser removido por políticas de retenção: esta solução não é substituto definitivo para uma chave de publicação guardada com segurança em GitHub Secrets ou para o Google Play App Signing.
- A versão 0.3 anterior tinha assinatura efêmera, portanto pode ser incompatível com a 0.4. Esse é um limite da versão anterior, não uma exigência para cada atualização futura.
