# Gear Render (Fabric 1.21.4, mod kliencki)

Mod tylko po stronie klienta. Na serwer nic nie instalujesz.

## Budowanie przez GitHub
1. Wrzuć całą zawartość tego folderu do repozytorium (pliki w katalogu głównym repo).
2. Zakładka **Actions** -> workflow **build** odpala się sam po pushu.
3. Gotowy jar: Actions -> ostatni run -> **Artifacts** -> `gearrender-jar`.
4. Wrzuć jara do `.minecraft/mods` razem z **Fabric API** (Fabric Loader >= 0.16, Java 21).

Lokalnie: `gradle build` (Gradle 8.11+), jar w `build/libs/`.

## Użycie
- `/gearrender` lub `/gr` otwiera GUI (klawisz domyślnie Right Shift). Klawisz **H** przełącza AutoTP. Oba do zmiany w Sterowaniu.
- **Ctrl + A** na przedmiocie w dowolnym GUI (rynek, skrzynia, ekwipunek) dodaje go do listy szukanych, drugi raz usuwa.
- Domyślnie szukane: cały netherite (zbroja, miecz, narzędzia) i elytra.

## Zakładki
- **Główna**: W pobliżu (TPA, STATS, + do Śledzonych) i Zniknięci (TPA, STATS).
- **Historia**: każdy, kto zniknął; set i item z ostatniego razu, + do Śledzonych, Usuń, wyszukiwarka.
- **Śledzeni**: lista na stałe, nic nie usuwa się samo. Własny przełącznik AutoTP, statystyki prób.
- **Ustawienia**: cooldowny, zasięg, dźwięk, lista szukanych przedmiotów.

## Wykrywanie zniknięcia
Po zniknięciu gracza z zasięgu mod wysyła `/tpa nick` i czyta odpowiedź z czatu:
- `Wysłano prośbę...` / `Wysłałeś już prośbę...` -> online, zostaje w Zniknięci
- `Nie odnaleziono takiego gracza...` -> offline, wypada ze Zniknięci (zostaje w Historii i Śledzonych)

Wzorce (regex) są w `config/gearrender/config.json` (`sentRegex`, `alreadyRegex`, `notFoundRegex`, `acceptedRegex`).
Wyłączysz to w Ustawieniach ("Sprawdź /tpa po zniknięciu").

## Uwagi
- Klient widzi sprzęt tylko graczy w zasięgu renderowania.
- AutoTP wysyła prawdziwe komendy /tpa. Sprawdź regulamin serwera. Odstęp między komendami to 1,5 s.
- Gdy kilka próśb /tpa wisi naraz, ktoś może zaakceptować później i przeteleportować Cię dalej.
