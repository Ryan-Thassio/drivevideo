# Drive Vídeos (Android)
1. Google Cloud Console → crie projeto → ative "Google Drive API".
2. Tela de consentimento OAuth: adicione o escopo `drive.readonly` e seu e-mail como usuário de teste.
3. Credenciais → ID do cliente OAuth → tipo **Android**, pacote `com.example.drivevideo`, SHA-1 da sua keystore
   (`./gradlew signingReport`).
4. Abra a pasta no Android Studio, sincronize e rode.

## Compilar pelo celular (GitHub Actions)
1. Crie um repositório no GitHub e envie TODO o conteúdo desta pasta (inclusive .github e app/debug.keystore).
2. Aba Actions → "Build APK" → Run workflow. Aguarde ~5-8 min.
3. Abra a execução concluída → Artifacts → baixe DriveVideo-apk, extraia o zip e instale o APK.
SHA-1 da chave (use no cliente OAuth Android): 3F:EB:A5:EE:F8:91:98:57:4C:EF:28:69:01:2E:53:27:96:A9:03:BB
