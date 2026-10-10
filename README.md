# hermes-live-voice-mobile

Client Android (Kotlin, natif) pour la gateway [Hermes Live Voice](https://github.com/florentdanguiral/hermes-live-voice)
(provider Voxtral) — conversation vocale live avec l'assistant Hermes auto-hébergé,
plus wake word « Hey Hermes » (Vosk, modèle small-fr embarqué).

## Fonctionnalités

- WebSocket vers la gateway (protocole v6) : `session.start` → `audio.input` (PCM 24 kHz base64) →
  `transcript.delta` / `audio.output` / `response.*`
- Tour de parole géré côté serveur (VAD énergétique + gate de complétude mistral-small)
- Micro 48 kHz (VOICE_COMMUNICATION, annulation d'écho) décimé vers 24 kHz, trames de 50 ms
- Lecture TTS en streaming (AudioTrack 24 kHz, fréquence lue depuis `session.ready`)
- Half-duplex : l'envoi du micro est suspendu pendant la lecture du TTS (évite l'écho haut-parleur)
- Sélecteur de conversation (liste dérivée de `GET /v1/conversations`, reprise de session via `mode: resume`)
- Wake word « Hey Hermes » : Vosk + grammaire restreinte, actif quand l'app est ouverte et déconnectée
- Réglages : URL WebSocket + jeton (stockés dans les préférences privées de l'app)

## Réglages

À la première ouverture, l'app demande deux valeurs (stockées dans les
préférences privées du téléphone, jamais dans le code) :

- **URL** : WebSocket de la gateway, forme `wss://<hôte>/v1/live`
- **Jeton** : token d'accès de la gateway (`HERMES_LIVE_AUTH_TOKEN`)

Aucune valeur par défaut n'embarque d'URL ni de secret : le repo est publiable
tel quel.

## Build

```bash
# 1. Déchiffrer le keystore avec la clé age (secret GitHub KEYSTORE_AGE_KEY)
age -d -i <fichier clé age> keystore/hermes-live.keystore.age > keystore/hermes-live.keystore
age -d -i <fichier clé age> keystore/keystore.properties.age > keystore/keystore.properties
# 2. Build signé (debug ET release partagent la même signature : installs cumulatives)
./gradlew assembleRelease
```

La CI GitHub Actions (`.github/workflows/android.yml`) déchiffre le keystore
(secrets GitHub) et publie l'APK sur la prerelease `latest` à chaque push sur
main. Le keystore en clair et ses mots de passe ne sont jamais commités : seuls
les fichiers `.age` chiffrés sont dans le repo.

## Sécurité / vie privée

- TLS de bout en bout (wss), jeton en query param sur l'upgrade WebSocket
- Le micro ne diffuse que vers la gateway configurée ; aucune donnée n'est envoyée ailleurs
- Pas de télémétrie, pas de crash reporter, `allowBackup=false`
