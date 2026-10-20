# JasSI – asystent głosowy (Android, offline)

Mikrofon → whisper.cpp (STT) → polecenia sterujące telefonem lub Gemma 3 1B (rozmowa) → Android TTS (po polsku).

## Budowanie APK
- GitHub Actions: wrzuć repo na GitHuba → zakładka Actions → artefakt `JasSI-apk` (`app-debug.apk`).
- Lokalnie: `bash scripts/fetch_native.sh` → `gradle assembleDebug` (JDK 17, NDK 27.2.12479018, CMake 3.22.1).

## Modele (pobierają się same na Wi-Fi)
- Whisper – przycisk **Jakość Whisper**: Base (60 MB) / **Small (190 MB, domyślny)** / Large-v3-turbo (574 MB, najlepszy, wolniejszy).
  Starsza instalacja z Base zostanie sama zaktualizowana do Small.
- Gemma: `gemma-3-1b-it-IQ4_NL.gguf`.
- Ręcznie: `adb push plik /sdcard/Android/data/pl.jassi.app/files/models/`

## Jednorazowa konfiguracja „bez dotykania telefonu”
1. Zezwól na mikrofon, kontakty, telefon, SMS (zapyta przy starcie).
2. Przycisk **1** → Ustawienia → Dostępność → JasSI → włącz. (Android 13+: najpierw Informacje o aplikacji → ⋮ → „Zezwól na ustawienia z ograniczeniami”.)
3. Przycisk **2** → zezwól na wyświetlanie nad innymi aplikacjami (pozwala otwierać aplikacje z tła).
4. Przycisk **Bateria** → JasSI: bez ograniczeń.
5. **Tryb głośnomówiący** → mów „JasSI, …”.
Telefon musi być odblokowany (PIN/odcisk zrobi człowiek).

## Polecenia (przykłady)
- Aplikacje/strony: „otwórz YouTube”, „otwórz kalkulator”, „otwórz aplikację Allegro” (brak → Sklep Play), „wejdź na wp.pl”, „otwórz stronę onet”, „wyszukaj pogoda Katowice”
- Kontakty: „zadzwoń do mamy”, „zadzwoń do 500 123 456”, „znajdź kontakt Marek” (gdy pasuje kilku – JasSI dopyta)
- Wiadomości: „wyślij SMS do taty, że będę za 10 minut”, „napisz na WhatsAppie do Ani, że już jadę”
- Telefon: „odbierz”, „rozłącz się”, „nawiguj do Rynek Kraków”, „ustaw budzik na 7 30”, „minutnik na 5 minut”
- Dźwięk: „głośniej”, „ciszej”, „ustaw głośność na 40 procent”, „pauza”, „następny utwór”, „puść Perfect”
- Ekran: „wróć”, „ekran główny”, „ostatnie aplikacje”, „powiadomienia”, „szybkie ustawienia”, „zrób zrzut ekranu”, „zablokuj ekran”,
  „przewiń w dół”, „kliknij Szukaj”, „wpisz pogoda jutro”, „zatwierdź”, „co jest na ekranie”, „włącz Wi-Fi”, „włącz latarkę”
Wszystko inne → rozmowa z Gemmą.
