# Бриф для новой сессии: FableDroid + перенос правок из FIFA Street

Отвечать по-русски.

## Контекст

- **FableDroid** — порт Fable II на Android (ReXGlue: статическая рекомпиляция в arm64, Xenos поверх Vulkan). Автор оригинала: `hardsilinder/FableDroid`, версия 0.6.3 beta.
- **Форк пользователя:** `basarab1504/FableDroid`, ветка **`fifa-perf-port`** (один коммит поверх 0.6.3; `main` = оригинал).
- **Донор правок:** мой порт FIFA Street 2012 — `basarab1504/restreet-android-20261030`, ветка `claude/r30` (патчи `android/rexglue-*.patch`, журнал `PROJECT-STATUS.md`, скиллы `.claude/skills/`: `perf-investigation`, `device-log-analysis`, `rexglue-patch-stack`, `diagnostic-apk`).
- **Телефон для тестов:** Samsung SM-A566B, Exynos 1580 / Xclipse 540, Android 16. Для Fable в лаунчере выбирать **GPU drivers → System driver** (встроенный Turnip только для Adreno).
- Игра: Fable II GOTY, ISO снимаю со своего диска. Игровые файлы никогда не коммитить и не публиковать.

## Что сделано в ветке `fifa-perf-port`

1. `thirdparty/rexglue-sdk-perf-port.patch` — накладывается **после** `rexglue-sdk-android.patch` на SDK `himdo/rexglue-sdk` @ `cc5f03d` (у FIFA другой форк SDK, `rexglue/rexglue-sdk` @ v0.10.0, поэтому патчи переносились вручную).
   - Кэш загрузок CPU + пакетная повторная защита + «горячие» страницы + кэш из гостевой памяти (FIFA r25–r28).
   - Отдельная очередь present (r12).
   - `WaitMultiple` просыпается по сигналу (r22), таблица известных регистров (r25).
   - GPU- и аудиопоток на быстрых ядрах + ADPF.
   - Короткий путь resolve → текстура (r41/r43) со счётчиками причин отказа; **по умолчанию выключен**, в FIFA не подтверждён.
   - Логи: `log_frame_stats`, `log_late_frames`, `log_thread_cpu` (+ `log_thread_samples*`), `log_thermal`, `log_present_paints`, `log_audio_output`, `log_resolve_texture_loads`, логи ImGui-диалогов, бэктрейс необработанной ошибки доступа, неудачные/крупные выделения памяти. В GPUTIME добавлены сегменты `resolve`, `rt_dump`, `texload`, `texsnap`.
2. Лаунчер (`android/app/src/main/java/org/fable2/recomp/`): карточка **Performance** в `LauncherActivity`, ключи в `Settings.writeGameConfig`, `battery.log` раз в 15 с в `GameActivity` при включённом Diagnostic logging.
3. `docs/FIFA_PERF_PORT.md` — что перенесено, таблица логов, план первого теста.

Не переносилось: специфичное для FIFA (480p/360p, отключение MSAA, выборочный readback), перенос загрузок/обрезка проходов (в FIFA FPS не дали), FSR/CAS (r37–r39) — кандидат на следующий шаг.

## Статус — важно

- Изменённые файлы SDK **скомпилированы только хостовым clang под Linux x86_64**. Под Android/arm64 ничего не собиралось: в той сессии был недоступен NDK (dl.google.com заблокирован). Блоки `#if __ANDROID__` / `REX_ARCH_ARM64` взяты из FIFA без изменений, но против этого дерева не компилировались.
- Java лаунчера проверена только на синтаксис.
- **APK не собран, на устройстве ничего не запускалось.** Цифры в `FIFA_PERF_PORT.md` — из FIFA, не из Fable.

## Следующие шаги

1. Собрать APK (Mac/Linux, NDK 29.0.14206865, шаги в `README.md` / `android/README.md`, плюс `git -C thirdparty/rexglue-sdk apply ../rexglue-sdk-perf-port.patch`). Исправить ошибки сборки, если будут.
2. Первый тест: настройки по умолчанию + Diagnostic logging, ~10 мин с холодного телефона. Логи и `battery.log` в `Android/data/org.fable2.recomp/files`.
3. Разобрать лог как в FIFA (скилл `device-log-analysis`): FPS, поздние кадры, GPU время, температура и частоты, загрузка потоков, `CPU upload cache`/`Hot pages`, `Present paints`.
4. A/B по одной правке за раз: кэш загрузок выкл, отдельная очередь present выкл.
5. Короткий тест с resolve shortcut: картинка (bloom, тени, меню) + строка `Resolve texture shortcut` (hits / misses / skip-причины).

## Правила работы (из FIFA)

- Сначала измерить причину логом, потом чинить; выводы — только по прямым данным лога, с условиями замера.
- Новое поведение — за cvar; после любой правки смотреть CPU всех потоков и нагрев, не только FPS.
- Нагрев сравнивать только с холодного старта и при одинаковых настройках.
- Не заявлять «ускорение» без лога с телефона.
- Работа Claude — в отдельной ветке, не в `main`.
