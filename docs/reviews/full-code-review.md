# Vollständiges Code-Review: Voice Note Transcriber

## Umsetzungsstand nach freigegebenem Fix-Batch

Die nachfolgenden Findings dokumentieren den damaligen Reviewstand. Inzwischen umgesetzt:

- Alle vier P2-Punkte: zentraler prozessweiter Share-/Notification-Cancel inklusive
  blockierter Metadatenabfrage; bereinigte History-Wiederherstellungskopien; Dirty-Snapshot-
  Retry mit sichtbarem Fehler und Retry-Aktion; gemeinsame KeyStore-Injektion plus
  instanzübergreifender Read/Write/Reset-Lock.
- Kleinere Produktpunkte: vollständige Short-Reads vor Import-Grant/Persistenz; explizites
  API-Key-Entfernen und asynchrones Speichern; URI-/PCM-Mistralpfad mit gleichem frühem
  Cancellation-Marker und stückweisem PCM-Staging ohne komplettes Zwischen-ByteArray.
- Kleine Effizienzpunkte: Opus-Puffer ohne Teilkopie, Metadaten nur einmal, exakte
  gerundete PCM-Kapazität, nur Mel-Padding nullen, deaktivierte v3-Einträge ohne Provider-
  I/O, wiederverwendete Test-Logits.
- Alle fünf benannten Test-Trims durchgeführt (Assertions erhalten/zusammengelegt;
  Timingprobe in explizitem `:app:resamplerBenchmark`). Neue gezielte Regressionen erhöhen
  die Gesamtzahl trotzdem: aktueller integrierter Standardlauf 272 Tests ohne Fehler.
- Worker-Barrieren statt Start-Sleeps, echter nächster Cache-Acquire für Lease-GC, ehrlicher
  Wiederverwendungstest statt unbelegter Rehash-Behauptung und deterministische Uhr für
  tatsächlichen Zwischenfortschritt. History-Testexecutors werden beendet.
- Verpflichtende synthetische Decoder-Fixtures mit gehashten Referenzen, Sample- und
  Signalprüfung; reale test-only Binder-Provider prüfen Offset/Length/FD-Position sowie
  Pipe-Verweigerung/Fallback/Cleanup. Native Read-Cancellation jetzt innerhalb eines
  einzelnen Reads nach bereits ausgegebenem PCM geprüft. Keine privaten Fixtures im Repo.

Bisherige Nachweise des Fix-Batches: Offline-Debug- und Instrumentierungs-APK gebaut;
272 Standard-JVM-Tests grün; expliziter Resampler-Benchmark ausgeführt; 15 native Hosttests
grün. Synthetische Signal-/Providerchecks auf allen drei Geräten bestanden. Lange und
kurze echte Notiz auf allen drei Geräten erfolgreich, alle sechs Texte exakt gleich wie
vor dem Fix-Batch. ACTION_SEND-Autostart mit dem echten Testprovider auf allen drei
Geräten erfolgreich (Fold-Provider musste nach Prozessende neu gestaged werden).

Das Integrationsreview fand zusätzlich einen Main-Thread-Lockkonflikt beim synchronen
Key-Statusabruf. Auch dieser ist jetzt asynchron: `statusAsync()` auf IO, suspendierende
ViewModel-API und `LaunchedEffect` mit Lade-/Fehlerzustand im Dialog. Der neue
UI-Dispatcher-Heartbeat-Test blockiert den Key-Read per Barriere, wurde mit synchronem
Status tatsächlich rot (Timeout) und besteht mit IO-Dispatch. Die letzte Fassung ist auf
OnePlus und Fold installiert, per APK-SHA-256 bestätigt und ihr Dialog geprüft. Das
Note10+ wurde vor dieser letzten Dialogänderung vom USB getrennt; dort ist die unmittelbar
vorherige, aufwendiger getestete Fassung installiert. Das aktuelle APK liegt lokal bereit.

Zusätzlich auf dem Note10+: echter Android-Keystore über die UI mit ausschließlich
einem synthetischen Schlüssel gespeichert und wieder entfernt (vorher kein Nutzer-Key);
History-Schreibfehler reversibel am Temp-Pfad injiziert -> sichtbarer Retry -> ohne neue
Bearbeitung korrekt gespeichert und Fehleranzeige entfernt. Bestehende Nutzer-Keys auf
anderen Geräten wurden nicht verändert; keine Cloud-Anfrage für diese Prüfungen.

Bewusst nicht umgebaut: die größere Compose-Progress-/Elapsed-Abonnementstruktur ohne
Profiling-Nachweis. Release-Offlinecache-Blockade bleibt unverändert. Spezifikations- und
Qualitätsreviews sind bestanden; das gezielte Re-Review des zusätzlichen Status-Lock-
Befunds hat ebenfalls APPROVED erteilt. Keine verbleibenden P1/P2-Blocker in den geprüften
Änderungen. Die Änderungen werden ausschließlich lokal committed; kein Push.

## Umfang und Urteil

Drei unabhängige Reviews des aktuellen vollständigen Codes: Audio/Inferenz/Native/Cache;
App-Zustände/Lifecycle/Share/History/Cloud; gesamte Testsuite. Ausgangspunkt: Commit
`98adfbc` plus native Opus-Implementierung. Die danach behobenen nativen Findings sind
unten getrennt gekennzeichnet. Keine Pushes und keine Live-Cloud-Anfragen im Review.

Inventar: 34 Kotlin-Produktionsdateien, 6.278 Zeilen vor den letzten kleinen Reviewfixes;
28 JVM-Testdateien plus gemeinsame Fakes. Letzter ausgeführter JVM-Lauf: 237 Tests,
0 Fehler/0 übersprungen, aggregierte Testzeit rund 4 Sekunden. Eine hohe Methodenanzahl
allein ist deshalb kein Overengineering-Nachweis.

Keine belastbaren P0/P1-Produktionsfehler im untersuchten Stand gefunden. Vier P2-Themen
in App-/Datenhaltung bleiben offen. Das ist kein Beweis ihrer Abwesenheit in anderen
Szenarien; statische Befunde und tatsächlich ausgeführte Prüfungen sind getrennt.

## Bereits behoben und geprüft

1. Native Header-/Metadatenverarbeitung war zunächst nicht begrenzt/abbrechbar.
   Jetzt: Cancellation auch in nativen Read-/Seek-Callbacks; pro Open/Read 2 MiB
   Lesebudget, 2 s Verarbeitungsdeadline, 8.192 Callback-Prüfungen; bestätigtes Opus
   maximal 64 MiB, insgesamt maximal 128 MiB gelesen. Bereits blockierendes Kernel-/
   Provider-pread kann dadurch nicht vorzeitig unterbrochen werden; diese Grenze bleibt.
2. Android-Tagged-Pointer können negative Java-long-Werte ergeben. JNI-Handles und
   Fehlerstatus sind getrennt; ausschließlich Handle 0 bedeutet Öffnungsfehler.
3. Die Opus-Eingabegrößengrenze durfte große Nicht-Opus-Dateien nicht sperren.
   Nun erst begrenztes `op_test_callbacks`, dann Größenprüfung für erkanntes Opus,
   danach `op_test_open`. Eine gültige 14-Minuten-WAV mit 80,64 MB nimmt auf allen drei
   Geräten den Android-Fallback und liefert genau 13.440.000 Samples bei 16 kHz.
4. MediaExtractor wurde vor einem möglicherweise fehlschlagenden Descriptor-Open angelegt.
   Descriptor jetzt zuerst unter `use`, danach Extractor mit unmittelbarem `finally`.
5. Die Instrumentierung prüft nun ausdrücklich `native-opus` für gültiges Opus und
   `android` für WAV/MP3, statt stillen Fallback als Fast-Path-Erfolg zu zählen.

Ausgeführt: 13 native Hosttests; 35 Decoder-Fixtures auf jedem von OnePlus 12, Fold 4 und
Note10+, inklusive großer WAV, übergroßer Tags, ungültiger Datei, Dauerlimit und Abbruch
während des nativen Öffnens. Zusätzlich UI-Transkription auf allen drei Geräten sowie
Background/Cancel/anschließender Neustart auf dem Note10+. R8-JNI-Keep-Regeln,
16-KiB-ELF-Alignment, verpackte Bibliothek und BSD-Lizenztexte geprüft.

## Offene Produktbefunde, priorisiert

### P2: Notification-Cancel beendet wartenden Share nicht zuverlässig

Orte: `work/WorkNotifications.kt:118–121`, `AppViewModel.kt:75–97,147–150`,
`TranscriptionCoordinator.kt:541–549` (Pfade unter `app/src/main/java/com/local/voicenotes`).

Der Receiver ruft nur `coordinator.cancel()` auf; der wartende `shareJob` gehört dem
ViewModel. Ist der Coordinator noch Idle, etwa während Metadaten/Katalog laden, beendet
der Notification-Knopf diesen Share nicht. Danach kann die Transkription doch starten;
Cloud-Upload nur bei bereits erteiltem Consent, kein nachgewiesener Consent-Bypass.

Minimalfix: Pending Share zentral im AppGraph über einen abbrechbaren Token/Job verwalten.
UI- und Notification-Cancel müssen denselben Pfad samt Service-Reservation beenden.
Regression: Share warten lassen -> Notification-Cancel -> Metadaten freigeben -> keine
Transkription und keine übrig gebliebene Reservation.

### P2: Gelöschte History bleibt im Backup und kann wiederhergestellt werden

Orte: `history/TranscriptHistoryStore.kt:90–106,121–143,152–168`.

Delete schreibt den neuen Zustand, sichert zuvor aber die alte Hauptdatei einschließlich
des gelöschten Texts als `.bak`. Bei einer später kaputten Hauptdatei kommt der gelöschte
Eintrag zurück. `.corrupt` kann ebenfalls alte sensible Inhalte dauerhaft behalten.

Minimalfix: Löschungen auch in Wiederherstellungskopien berücksichtigen; bereinigtes Backup
atomar schreiben und Diagnosekopien nach expliziter Löschung entfernen. Regression:
add -> delete -> Hauptdatei beschädigen -> neu laden -> Eintrag bleibt gelöscht.
Das ist keine Garantie forensischer Löschung auf Flash-Speicher.

### P2: Fehlgeschlagene History-Persistenz wird weder zuverlässig wiederholt noch angezeigt

Orte: `history/TranscriptHistoryStore.kt:99–106`, `history/TranscriptHistory.kt:81–99`.

RAM-Cache wird vor dem Schreiben ersetzt, Schreibfehler werden nur geloggt. Die
Edit-Warteschlange wurde bereits geleert. Ein späteres flush ohne neue Edits versucht
nicht erneut zu schreiben; nach Neustart können Änderungen fehlen oder Löschungen
zurückkehren, obwohl der Speicher inzwischen wieder verfügbar ist.

Minimalfix: Dirty-/Fehlerstatus behalten, neuesten Snapshot bei flush erneut schreiben und
Speicherfehler sichtbar melden. Regression mit einmaligem Schreibfehler, anschließend
behobenem Storage und erneutem flush ohne zusätzliche Änderung.

### P2: UI und Backend synchronisieren API-Key-Zugriffe nicht gemeinsam

Orte: `AppGraph.kt:55–56`, `inference/MistralTranscriptionBackend.kt:45`,
`inference/MistralApiKeyStore.kt:88–100,127–142`.

Zwei Store-/Repository-Instanzen teilen SharedPreferences/Keystore, aber ihre
`@Synchronized`-Locks nicht. Ein paralleler Read/Write kann Ciphertext und IV aus
verschiedenen Versionen lesen. Der anschließende Unreadable-Reset kann einen gerade neu
gespeicherten Key entfernen. Besonders nach Cancel kann UI-Write mit noch laufendem Read
überlappen.

Minimalfix: Eine gemeinsame Store-Instanz injizieren oder einen gemeinsamen Lock für
Read/Write/Reset verwenden. Regression mit kontrolliert verschachtelten zwei Clients.

## Weitere kleinere Produktpunkte

- `model/ModelRepository.kt:155–156`: Ein einzelnes read muss keine acht Bytes liefern.
  readFully/Read-Loop statt einen legalen Short-Read als beschädigtes Modell abzulehnen.
- `MainActivity.kt:505–507`: Kein UI-Weg zum Entfernen des API-Keys, obwohl der Store
  blankes Set als Löschen unterstützt. Explizite Entfernen-Aktion anbieten.
- `inference/MistralTranscriptionBackend.kt:81–94,110–134`: Der selten benutzte PCM-
  Kompatibilitätseinstieg setzt keinen activeRequest-Marker vor Konvertierung/Staging.
  cancel() kann dort vor HTTP-Start wirkungslos bleiben; Coroutine-Cancellation besteht.
  Beide Einstiege unter denselben Request-Marker stellen.

## Effizienz: nach Nutzen/Kosten

1. Geringer Aufwand: Opus `resampler.process(buffer,0,n)` statt `copyOf(n)` pro Teilblock.
   Reduziert Allokationen/GC; konkrete Laufzeitverbesserung noch nicht gemessen.
2. Geringer Aufwand: Bei bekannter Framezahl PCM-Accumulator exakt auf die gerundete
   Ausgabelänge dimensionieren statt +4096. Spart im Erfolgsfall die Schlusskopie.
3. Geringer Aufwand: Beim Ultra-Eingabepuffer nur den Paddingbereich nullen, der Nutzbereich
   wird überschrieben. Kleiner Speicherbandbreitengewinn, keine Messzahl behauptet.
4. Geringer bis mittlerer Aufwand: Keystore-Schreiben vom Main-Thread nach IO verlagern;
   Save-Zustand/Fehler anzeigen. Vermeidet mögliche UI-Hänger.
5. Geringer bis mittlerer Aufwand: Dauerhaft deaktivierte v3-Einträge ohne erneutes Öffnen
   jedes ContentProvider-Descriptors darstellen. Weniger Start-/Share-I/O.
6. Mittlerer Aufwand: Elapsed-/Progress-Anzeige getrennt abonnieren, 250-ms-Clock nicht
   pauschal durch den gesamten Screen reichen. Zuerst Recomposition messen.
7. Tests: Beim MAX_STEPS-Test unveränderte synthetische Logits wiederverwenden; vermeidet
   unnötige Test-Allokationen, ohne Assertions zu streichen.

Keine dieser optionalen Optimierungen wurde stillschweigend umgesetzt oder als gemessener
Speedup ausgegeben. Der belegte große Gewinn stammt bereits vom nativen Opus-Decoder.

## Tests: Qualität statt Stückzahl

Keine starke Kürzung empfohlen. Cache-Integrität (auch gleiche Größe/mtime), Cold-Start-
Marker, Leases, Cancellation/Retry-Gates, Coordinator-Races, Consent, URI-Schemes,
Lockscreen-Vorschauen, Key-Migration, Uploadlimits und native Parserfälle unbedingt behalten.

Wichtige Lücken/Überversprechen:

- Geräte-Decoderchecks: Route/finite/nonempty sind keine vollständige PCM-Validierung.
  Großer WAV-Fall hat jetzt exakte Länge; langfristig kleines verpflichtendes synthetisches
  Fixture-Manifest mit Samplezahl und Signalvergleich ergänzen. Private Noten optional.
  Hosttests vergleichen bereits genaue Länge und PCM; das ersetzt nicht die Android-JNI-
  Integrationsprüfung. Aktuell nur file://, kein echter SAF-Offset-/Pipe-Provider-Test.
- Consent wird als Entscheidungsregel geprüft, nicht ausreichend als tatsächliche Adapter-
  Durchsetzung. Zählender Fake-Backend-Test für direkte Transcribe-/Share-Aufrufe mit
  verweigertem/erlaubtem Consent; keinen echten Cloud-Upload dafür starten.
- `ModelFileCacheReviewRegressionTest.trustedBlobPathSkipsTheBlobRehash`: gesunde Daten
  und Korruptionszähler beweisen nicht, dass kein Rehash stattfand. Blob-Lesezugriffe zählen
  oder den Test als Wiederverwendungstest benennen.
- `ModelFileCacheTest.replacedBlobIsAlsoCollectedByTheNextAcquireOfAnotherSource`: prüft
  aktuell sofortiges forget statt den namensgebenden nächsten Acquire. Echte Lease-
  Freigabe/anschließenden anderen Acquire prüfen.
- `native/tests/test_decoder.py:test_cancellation_is_checked_inside_native_read`: Flag
  ist schon vor vn_read gesetzt; beweist nur Eintrittskontrolle, nicht mitten im Read.
  Cancellation während open wird dagegen bereits innerhalb der Callbacks getestet.
- `OpusPcmTest.progress is monotonic...`: Bei kurzem Input entsteht eventuell nur 1f.
  Zwischenfortschritt mit injizierbarer Uhr testen; bisherige Abschlussprüfung behalten.
- CancellationTest.delay(20), Gate-Test mit negativer 200-ms-Abfrage und exakte
  StateFlow-Spur im ShareDuringImport-Test: Startbarrieren statt Timingannahmen nutzen.
- TranscriptHistoryTest: Eigene Executor-Instanzen im Teardown schließen.
- ModelSelectionTest v3-import rejection: Fake wirft selbst den erwarteten Fehler.
  Beweist Fehlermeldung/Selektion, nicht dass Produktionsimport keine Grants/Records schreibt.

### Konkreter kleiner Trim-Vorschlag, noch nicht angewandt

1. `TranscriptHistoryTest.commitKeepsASyncedBackupOfThePreviousFile` streichen: dupliziert
   den Store-Test `writesAtomicallyAndKeepsTheLastVersionAsBackup`; beweist kein fsync.
2. `ParakeetUltraModelsTest.catalogRefreshReEvaluatesUltraRecords` in
   `ModelRecordsTest.enabledIsReEvaluatedOnEveryRead` integrieren, Assertions erhalten.
3. FastPath-Test `failingSourceDuringVerificationCountsAsFailedVerification` mit
   `failedVerificationKeepsTheMarkerUntilTheFullPathRepairsTheCopy` zusammenlegen;
   zusätzliche assertNull(recorded(source))-Assertion übernehmen.
4. Resampler `oneMinuteHasNoDriftWith1024FrameBuffers` in
   `totalLengthMatchesRoundedRatioForCommonRates` integrieren; exakte Länge verlangen.
5. `fifteenMinutesAt48kIsFastEnough` aus Standardsuite in optionalen Benchmark auslagern:
   hostabhängiger Zeitgrenzwert ist keine deterministische Funktionsprüfung.

Damit würden fünf Standard-Testmethoden entfallen (237 -> 232 vor neuen Regressionen),
ohne die benannten Szenarien aufzugeben. Keine Tests nur zur kosmetischen Zahlenreduktion
löschen. Die vorgeschlagenen neuen Regressionen haben mehr Wert als größere Kürzungen.

## Verifikation und offene Buildgrenze

- Debug-APK und Instrumentierungs-APK offline gebaut; 237 JVM-Tests grün.
- 13 native Hosttests grün; 35 Decoder-Fixtures auf jedem von drei Geräten grün.
- Finaler langer UI-Lauf mit abgesicherter Native-Fassung: 121,58-s-Note insgesamt
  OnePlus 4,85 s, Fold 5,79 s, Note10+ 13,78 s (Einzelmessungen, keine Bench-Mittelwerte).
- Vollkorpusvergleich: 29 deutsche Notizen, Mistral-small-Referenz, tatsächliches Android-
  Native-PCM im Desktop-Ultra-Pfad: WER 10,39% vs vorheriges Desktop-FFmpeg-PCM 10,36%.
  Mistral ist keine menschliche Ground Truth; Referenzmodell-Uneinigkeit ist keine gemessene
  Qualitätsuntergrenze.
- Release-Build nicht verifiziert: Offlinecache enthält die für diesen Variant aufgelösten
  Compose-1.7.5-Artefakte nicht. Keine Netzwerk-Nachinstallation/Toolchainänderung dafür.

Empfohlene Reihenfolge für den nächsten Fix-Batch: vier P2-App-/Persistenzthemen mit
gezielten Regressionen; danach Short-Read/Key-UI und kleine allokationssparende Änderungen;
Testtrim in separatem, leicht überprüfbarem Commit. Größere UI-/Pipeline-Umbauten erst
nach messbarem Nutzen.
