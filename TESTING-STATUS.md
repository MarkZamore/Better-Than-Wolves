# Состояние проверок Better Than Wolves

## 14 ноября 2026 — команды гостя в Survival

Опубликованный baseline `bb248a8`, Java 17.0.20.1, Legacy Fabric 0.18.2, e4steam
`0.3.4-lf164.4` SHA256 `38a24bca7cc27769e79bfcf349ed23cd549638caa6961ac57a6e9f79bd0423a8`.
С разрешёнными командами штатный `time set day` прошёл у хоста и был отклонён
для синтетического гостя без OP. Новый bridge 1.0.0, 25 353 байта, SHA256
`59e90f91412f3ade2afed296f75d25734585ba1d3532c58ae5fb30e3e3268162`.
В новом изолированном Survival с командами обе проверки прошли. В отдельном
Survival без команд оба вызова отклонены. Хост — настоящий серверный игрок,
гость — новый объект серверного игрока без сетевого входа. В обоих мирах
штатная кнопка открыла слушатель и вывела штатное русское сообщение. Реальный
рендер строки мультиплеера показал синтетические название мира и ник хоста.
Офлайн-проверка команд отключала Steam bootstrap. Два Steam-аккаунта и вход
или переподключение настоящего гостя в этой проверке не использовались.
32 Java-проверки и 54 Python-теста прошли. Авторские JAR и общий e4steam те же.

Дата подготовки: 4 ноября 2026 года. Платформа: Minecraft Java 1.6.4 / Legacy Fabric 0.18.2 / Java 17; CE 3.1.1 / Nightmare Mode 1.1.6.

## Проверка обновления 8 ноября 2026 года

### Дополнительная диагностика отсутствующих звуков и admin-ника

**Позднее подтверждение причины OpenAL у гостя, 8 ноября:** получен полный
`AudioCheck-start.log`. Все SHA Java/LWJGL/DLL верны; Unicode Win32 загрузка
успешна, но LWJGL возвращает 126 на коротком кириллическом пути. Воспроизведение
на Windows ACP1251 подтвердило конфликт `file.encoding=UTF-8` и ANSI native
загрузчика LWJGL .17. Default Cp1251 проходит, UTF8 на той же кириллической папке
не проходит. Предварительный `System.load` exact DLL по Unicode-пути сохраняет
UTF8 и позволяет `AL.create`; отдельный child URLClassLoader тоже проходит.
Это адресные native пробы без физического звука/мира, не полный Knot-сеанс.
Пользователь подтвердил появление звука у anuvenn после переименования папки
лаунчера с кириллицы на английское имя. Её локальный сбой устранён этим обходом;
общий исправляющий bootstrap передан в LANMinecraft и здесь не объявлен
выпущенным. Author JAR, native DLL, настройки и игровые content locks не менялись.

В реальном запуске через лаунчер 8 ноября 16:57 после `KnotClient` были только
`--width/--height`, без унаследованных аргументов игры. Read-only снимок клиента
показал `field_6270=Instances/Better Than Wolves/assets` (папки нет) и 482
addon sound keys без vanilla `random.click`, шагов, песка, дождя и zombie.say.
Проверены SHA1 всех 1120 objects и 1120 virtual aliases: отсутствующих или
повреждённых файлов нет. Ошибка отсутствующего пути assets передана в чат
LANMinecraft для исправления общего наследования аргументов; правила по имени
сборки и ссылка из личного instance на общий assets не добавлялись. Прямые QA
ранее задавали правильный путь явно, поэтому не покрывали этот production-дефект.

Общий фикс опубликован в лаунчере **483**, revision
`efef00c2dbfd232f8a14c87513dd2fec9f440477`; его release workflow
`42480264299` завершился успешно. Новый pack publication требует полного
публичного download proof лаунчера не ниже 483 перед своим manifest. Это
поставка общего исправления, не новый GUI/два Steam-пользователя PASS этого QA.

Итоговый выпуск pack-latest `bb248a8b8ecd18366ca3c99fa013dbe2b2b9ad3d`
прошёл [pipeline 42481878111](https://github.com/MarkZamore/Better-Than-Wolves/actions/runs/42481878111).
Полностью скачаны/сверены EXE и дельты launcher483 (159343454 байт), затем все
8 pack assets (64879360 байт), 7 active files/11 mod rows; payload перед manifest.
Manifest SHA256 `2cf899908c635be6d3e6f80fa100263cb0a7b71a1aa63f0fc28b1b7c77ea6060`.
Bounded delivery artifact 38683 байта скачан отдельно, проверены hashes его
вложенных proof files; live GitHub API подтвердил точный состав assets/digests.
Начальный publish корректно остановил guard изменённого workflow supportSHA;
пересмотрен только этот pin, остальные support/content pins сохранены.
Новая диагностика хоста воспроизводима, а native загрузка OpenAL на госте и
два реальных Steam-участника V4 остаются NOT RUN по решению пользователя
публиковать без ожидания дальнейшего гостевого отчёта.

Новый воспроизводимый native audio probe сравнил один состав Java17 / LF0.18.2 /
CE3.1.1 / NM1.1.6 / bridge `04e344…` с аргументом `--assetsDir` и без него.
Оба процесса штатно завершились, миры не открывались. Отрицательный контроль:
482 sound keys / 0 streaming / 0 music; запись содержит только ±1 PCM16 LSB
округления, 0 samples выше этого уровня. Положительный: 1083 / 12 / 16;
щелчок, шаги по траве/камню/песку, zombie.say, дождь и музыка дали сигнал выше
отрицательного фона. SHA256 положительной 16-секундной WAV:
`c33e6ab93034ed207232022347dead7d5a79ab034464b2785d29fe538413a6f5`.
Независимый хвост музыки после максимальной 2-секундной rain OGG плюс 0,5 с
запаса дал RMS 0,0007155 и 327407 samples выше 1 LSB. Захват ограничен только
деревом QA-процесса; микрофон, весь рабочий стол и пользовательские настройки не
записывались/не менялись. Это проверка PCM/декодирования и native playback API,
не физическое прослушивание наушников или естественное расписание музыки в мире.

У старого живого javaw OpenAL и активная Windows audio session использовали
выход монитора, хотя все три default roles Windows указывали на наушники.
Новые процессы с теми же java.exe/javaw.exe и неизменённой OpenAL DLL
`1e4fe04805bc7b9d2488250aaa12b01ea8230cc9b51a356bb1376e180e6b1816`
открыли наушники. Начальную причину выбора монитора задним числом не установили;
персональную маршрутизацию/громкость скрипты не сбрасывали. В новом гостевом
отчёте anuvenn по-прежнему `Could not locate OpenAL library` и `No Sound`:
это отказ загрузки native DLL, а не доказательство неверного устройства.
Точная причина на её ПК остаётся UNCONFIRMED без native inventory/loader errors.
Скрипт `Test-LegacyAudioLoad.ps1` и обёртка `Run-AudioCheck.cmd` прошли проверку
на установленной Java17 хоста; этот PASS не переносится на гостя.

Отдельно опубликован общий e4steam `legacy-fabric-164-v4` / active V6,
JAR 2291728 байт SHA256
`38a24bca7cc27769e79bfcf349ed23cd549638caa6961ac57a6e9f79bd0423a8`.
`chat.type.admin` теперь отображает выбранный ник отправителя `/gamemode`;
canonical аргументы, вложенное сообщение и права не менялись. Native reentry,
сохранение/выход: exit 0 за 27,87 с; перевод admin/tp и native sharing controls
проверены. Первый fresh cycle создал независимый мир, но QA остановился на
английском языке против ожидаемого русского; исправлен только язык стенда,
затем reentry прошёл. Четыре CI jobs, 55 Java assertions, 47 Python tests и
независимое полное скачивание 20 JAR/4 новых release assets прошли. Main e4steam
`86b8d9ef9a0e0316567231b355f383c2a794f4ec`; frozen target `6008bdb…`.
Два реальных Steam-участника и реальная guest `/gamemode` доставка этой ревизии
NOT RUN. Нового EXE/контентного payload в этом QA-only изменении нет. Прежние
восемь ошибок текстур, Realms и skins.minecraft.net в baseline сохраняются.

Bridge 1.0.0: 25 200 байт, SHA256 `04e3441753b061d6e426ef117fe50ccd8f07892740b04fd1a397b98de72e06e2`. Оба author JAR и вложенные библиотеки прежние; их exact-SHA guards действуют. `native-create-03`: настоящий клиент Java 17 / Legacy Fabric 0.18.2 создал новый изолированный Creative-мир, вошёл, сохранился и завершился exit 0 за 43,08 с. `native-reenter-04`: повторный вход/save/quit/exit 0 за 43,39 с с общим транспортом `0.3.4-lf164.3` SHA256 `8a80ab8542dc87c141c013ca4f27fd5a8b190f2d3110339813d20cee5e2069d1`.

- PASS: новый мир предлагает только authored difficulty index 2, селектор выключен; прямое повторное действие не меняет сложность и режим игры. Creative выбирался штатной кнопкой. Native Share получил Creative/commands=true; контрольный Survival получил Survival/commands=false. Nightmare скрывает прежние кнопки; «Жалобы» и авторский quit gate сохранены.
- PASS: native chat/font hit-test нового e4steam направил copy/invite/stop и отклонил старую строку сессии. Физическая мышь не использовалась; выполнение команд и Steam в этом fixture подавлены только QA-агентом. Настоящая `commands.tp.success` показала два зарегистрированных синтетических ника, сохранив исходные canonical arguments.
- PASS: 30 проверок bridge; 15 фактических Loader records, 11 библиотечных mod ID. Снимок actual inventory снят по финальному повторному входу. Ранний кандидат неверного native symbol был отклонён Mixin guard; неверный профиль раннего стенда отклонён native-owner guard. Guards исправлены по точным symbols/синтетическому launch context, а не отключены.
- ERROR baseline: восемь прежних SEVERE текстур, недоступность Realms/старого skins.minecraft.net. Fatal markers в успешных двух сеансах отсутствуют. Генератор GPU atlas завершён в штатном разрешении без собственных ограничений.
- NOT RUN: два реальных Steam-участника на этой ревизии, приглашение/команды реального гостя, чужой скин на хосте и длительная слышимая проверка звука. Native fixture не выдаётся за такой PASS. Отчёт гостя с `Could not locate OpenAL library` сохраняется как отдельная незавершённая диагностика; исправная копия DLL на другом ПК её не закрывает.

Read-only диагностика портала в активном клиенте по прямому запросу владельца нашла две рамы из `nmSpecialObsidian` ID 2308 (Arena Stone). Для Nether авторская проверка требует обычный/crude obsidian ID 49. Авторская механика портала не менялась; диагностический агент не регистрировал transformers и не менял мир. Инструменты находятся отдельно в `tools/diagnostics`, не в игровом payload.

Дополнительная приёмка: `native-reenter-07`, exit 0 за 30,67 с, повторил native sharing/перевод имён, затем подтвердил `LibraryLWJGLOpenAL`, созданный OpenAL context и читаемый `random.click` через родной sound pool. После 200 ticks выполнены native save/quit. Две ранние попытки новой диагностической проверки остановились на неверном API/статическом поле самого QA-инструмента; игровая поставка не менялась. Слышимый звук, длительная игра и ПК гостя этим не проверены.

Лаунчер 476 (`3a6d3aa378179f85ec83425e5c22bd4afc6d776a`) сохраняет и восстанавливает объявленные индексом virtual/map-to-resources asset copies. На настоящем legacy index восстановлены 1120 файлов из проверенных объектов офлайн, повторный проход не изменил ни одного файла. Независимый production cold prepare прошёл; удаление одного `random.click` в его кеше и повторный `PackRuntimeService.PrepareAsync` восстановили точный SHA256 при неизменном prepared timestamp/schema 6. Личные миры и исходная установка не использовались для записи. Это проверка общих служб установки, не запуск публичного EXE через GUI.

Публикация `pack-latest` revision `d65a7f7f7abb9e3e8c828f728e3e033d8e207aed` прошла [delivery pipeline](https://github.com/MarkZamore/Better-Than-Wolves/actions/runs/42413955681). Независимый `verify_published.py` полностью скачал и сверил все 8 assets (64 879 359 байт), manifest SHA256 `69ad121d0686450ae94a5dfc20e3c90e43d8264d8f11afb14ca022de9d858583`, 7 active files и 11 mod rows. Сначала опубликован payload, затем manifest. Отдельно полностью проверены EXE/все дельты launcher 476 (159 395 207 байт) и все 20 выбранных JAR/4 новых release assets e4steam V5. Свежий каталог GitHub сохраняет прежние ID, путь и источник карточки. Public delivery PASS не расширяет перечисленные выше границы runtime-приёмки.

## Проверенные входы

Оба author JAR скачаны с официального Modrinth CDN по указанным пользователем версиям; официальные SHA512 совпали. CE primary `btwce-3.1.1.jar`: 48,017,821 байт, SHA256 `ef2b16a0183d810f3c9c526758fc56a2ef3ef540346d2e1cc442f7f9f892739d`. Nightmare `Nightmare-Mode-1.1.6.jar`: 12,053,870 байт, SHA256 `be8528a5e834faac4d999cfac9038a85f144500521ba9f8e4c19c035b941d248`. Официальный Minecraft client: 4,745,096 байт, SHA256 `f4513e51c766cdd1d32ca8acd0835d7e16e2851ebea3f2a3b4ce5ae696baf3ae`; Mojang SHA1 совпал.

Метаданные CE требуют Fabric Loader >=0.18.0, Java 17–21 и Minecraft 1.6.4. Nightmare version API явно требует именно CE version `zLnGXEI4`. Primary CE включает все собственные вложенные зависимости; intermediary/javadoc не являются устанавливаемыми дополнительными модами.

## Проверка обновления 6 ноября 2026 года

Bridge 1.0.0, 23 334 байта, SHA256 `16765c1b038b23dccb64ca1f968a4116930e818aa520f5197f0eb94e6c47cb5a`, запущен с Legacy Fabric 0.18.2, Java 17 и e4steam `0.3.4-lf164.2` SHA256 `b12737cbd0c3383c4d49fa2a0b32a6242293527ddd0fcce8472e1f3eaaace707`. `session-fixes-native` прошёл реальное создание Creative-мира, вход с native host identity proof, 200 игровых ticks, сохранение и штатный exit 0 за 36,73 с. `session-fixes-reentry` загрузил сохранённый мир, повторил проверки и завершился с exit 0 за 31,32 с. Учётная запись и мир синтетические. Loader зарегистрировал 15 записей, 11 входят в состав карточки. 29 адресных проверок bridge прошли.

- Через кнопку паузы Creative передался в native аргумент гостевого режима. В контрольном Survival передался Survival. В обоих случаях команды выключены, авторские кнопки режима и команд скрыты. Native чат показал «Порт локального сервера: 25565» на русском. При синтетическом состоянии публикации «Жалобы» открыли штатный экран с сообщением о недоступности и вернули в ту же паузу. Авторская блокировка выхода проверена.
- Оба exact-hash redirect для имён в объявлениях о достижениях применились при загрузке классов CE и Nightmare. Отправка фактического объявления о достижении не проверялась.
- Модификации e4steam были загружены, открытие сокета и запуск Steam отключены только агентом QA. Порт и признак публикации синтетические. Реальный вход Steam-гостя, имена в сетевом чате/при смерти и внешний вид его скина на стороне хоста имеют статус NOT RUN для этой ревизии.
- Fatal markers отсутствуют. Остались восемь исходных SEVERE текстур, недоступность Realms и старого `skins.minecraft.net`. Изолированный запуск без файла профилей лаунчера также сообщил об его отсутствии.

## Предыдущая production-проверка

| Проверка | Статус |
|---|---|
| Exact upstream downloads | PASS официальных hashes, не игровой PASS |
| Исходный full client | `baseline-create-02`: фактический native create/world join, 200 world ticks, штатный save/quit и exit 0; 26.78 с. Независимый новый QA-мир, авторские GUI defaults сохранены. Final bridge/launcher этим не проверены. |
| Исходный dedicated server | `baseline-server-01`: native Done, штатный stop и exit 0; 22.81 с. Отдельный loopback QA, не production роль и не full-client PASS. |
| Финальный состав, полный клиент | Production CmlLib preparation с настоящими runtime/instance/profile/SkinService/notice agent: `production-fresh-07` создал новый QA-мир и штатно сохранился/вышел за 48.47 с; `production-rename-09` повторно вошёл и завершил save/quit/exit 0 за 45.98 с. Helper 1.0.0 SHA `814347ffd616bfad8405618528953936280457e87d162803a639bc407a618172`, 18,996 байт; общий e4steam legacy port SHA `a35e616ee93e4cb9f6a9c2f0e9a06a36648e89639a07a6c47780b30c1b0c394e`. Все три source JAR и выбранный транспорт совпали с реально запущенным составом. |
| Финальный dedicated | `final-server-02`: Done/save/stop/exit 0 за 17.23 с; повторный вход в закрытый QA-мир. Состав сервера точно тот же, что upstream: два author JAR; client-only bridge и transport исключены по metadata. Это не двухклиентский сетевой PASS. |
| Собственный Steam-хост | Реальный вошедший аккаунт, native Open to LAN одним нажатием без Share-screen Start: опубликованный loopback, Steam ACTIVE. Метаданные собственного lobby имеют реального owner/current generation, имя настоящего мира и отображаемое имя из лаунчера. После native save/quit listener закрыт, Steam NONE, generation/tasks очищены. Только один ПК и аккаунт. Friendship/discovery другого участника и P2P между аккаунтами не выполнены. |
| Native Multiplayer и auth gate | Настоящий экран init/onClosed создаёт и удаляет lease. Native row-format fixture передаёт имя мира, ник и label Steam. Настоящий прямой TCP Packet2 протокола 78 с подставленным host alias отклонён: требуется authenticated Steam bridge, native player count остаётся 1. Fixture строки не выдаётся за обнаружение второго живого lobby. |
| Выбранный скин и native модель | Настоящий зарегистрированный профиль, loopback SkinService, 64×64 image, все 244 выбранных непрозрачных base texels совпали, native GPU texture создана. Динамически slim→classic→slim: обе старые textures освобождены, новые объекты и model cache правильны; исходный private registry восстановлен. В `production-fresh-07` inventory render сделал 67 native RenderSpecials вызовов с отсутствующим cape без NPE; проверка повторена в rename-09. Визуальная оценка и скин второго Steam-клиента не заявлены. |
| Identity / прогресс / смена ника | Настоящая source-world процедура C# Saves.Adopt/Stamp/ProfilePrep/Prepare, затем native reentry с другим nickname того же Steam-пользователя сохранили 7 именованных sticks, XP 17 и приручённого волка с прежним owner. Canonical username/session не изменились. Это не смена ролей двух Steam-участников, не прогресс двух разных игроков и не peer skin второго аккаунта. |
| Авторские эффекты имён | Native negative control: red apple 0. Выбранное имя Notch: ровно 1 native red apple на shadow player, не допущенном на сервер, настоящий хост не погибал. Dinnerbone: 84, Grumm: 87 native 180° Z rotations; deadmau5: 174 ear draw calls. Исходные сравнения, drops и geometry выполнялись родным кодом; canonical username/session оставались прежними. Это адресная проверка четырёх обнаруженных имён, не полный аудит всех name-based механик. |
| GPU / качество | Настоящий GL max texture 16384. Atlas blocks 1024×512 / 984 sprites и items 512×512 / 520 sprites; все native rectangles в bounds, rotated 0, настоящий stitch/upload завершён на Intel Arc B390. Текстуры, эффекты, анимации и distance settings не снижались. Это не оценка всех ресурсов/шейдеров. |
| Public release bytes и каталог | Не опубликовано |
| Подготовка обновления | 59 Python source checks, включая synthetic candidate update, corruption/cache checks, исключение QA build outputs, переносимую сортировку recipe, Windows/Linux byte-identity ZIP и bounded public-delivery verification. Несвязанный авторский ресурс принят при сохранении exact bytes обоих игровых JAR, descriptor, ownership и helper; source/locks не изменились. Guarded author SHA, recipe drift, пропавший bridge/overlay/receipt и author overwrite обязательного слоя отклонены. Это не Minecraft/runtime/реальный author-update PASS. |
| Воспроизводимая source-поставка | Чистый Git-index export восстанавливает все 6 active files из exact-hash официальных inputs/cache, самостоятельно компилирует helper тем же Temurin 21.0.12.1+1 в точный SHA `814347…`, выполняет 23 Java registry checks и 59 Python checks. Staging содержит 6 payload assets и отдельную библиотеку с 11 actual mod ID; все source bytes и итоговые manifest/archive bytes сверяются отдельно до commit/publication. Payload/ModList проверки не заменяют описанный выше runtime. |
| Принадлежность QA host | Все 8 source/content файлов host перенесены в `tools/runtime-qa/host`: 5 C# и PowerShell CLI сохраняют прежнюю логику; csproj требует явный внешний `LauncherProject`, README указывает новый путь. Сборка через настоящий внешний launcher project с `BuildProjectReferences=false` успешна, 0 warnings/errors. Production services не скопированы, игровые 6 files/helper/locks не изменены. Generated bin/obj/TestResults исключены из candidate scaffold. Это QA-only перемещение после native acceptance, не новый игровой PASS. |
| Проверка public delivery после публикации | Подготовлен обязательный CI шаг полного свежего скачивания launcher latest >=445 с EXE/всеми дельтами до pack publication и отдельного полного скачивания всех pack assets после неё. Пока workflow не выполнен, эти результаты не объявляются PASS. Финальные числа, hashes/revision и ссылка на небольшой proof artifact фиксируются в release notes; подготовительный статус здесь не выдаётся за локальное повторное скачивание. |

## Пресет управления

Сверены все 18 назначений с двумя реальными снимками `options.txt` исходного и финального клиента. Контексты CE, Nightmare, диагностики и EMI описаны в [проверке пресета](tools/keybinds/design.md). На отдельной копии настроек штатный `ControlsPresetService` восстановил два изменённых назначения, сохранил все остальные байты и повторно вернул 0 изменений. Прошли 54 Python-проверки, включая перенос пресета в candidate при обновлении автора. Новый игровой сеанс для этого изменения — NOT RUN.

## Наблюдавшиеся ошибки исходного состава

В `baseline-create-02` восемь SEVERE отсутствующих текстур: `btw:textures/blocks/nightmare:steel_ore_strata_2.png`, `btw:textures/blocks/nightmare:steel_ore_strata_3.png`, `nightmare:textures/blocks/nmTallFlower_dandelion8_top.png`, `nightmare:textures/blocks/nmTallFlower_dandelion8_bottom.png`, `nightmare:textures/blocks/nmTallFlower_voidShrub_top.png`, `nightmare:textures/blocks/nmTallFlower_hellFlower_top.png`, `minecraft:textures/blocks/honey.png`, `nightmare:textures/items/nmAwakenedStar.png`. Отдельно Realms `Server not available!` и `UnknownHostException: skins.minecraft.net`; исходная устаревшая skin-служба не доказывает работу выбранного в лаунчере скина. Fatal markers отсутствовали. Ошибки не скрывались и не понижались уровнем; их последствия для каждого ресурса визуально не оценивались.

В dedicated baseline наблюдались client-target `net/minecraft/class_319` ClassNotFound warning, отсутствующие first-run `ops.txt`/`white-list.txt` и `SocketException: Socket closed` при штатной остановке. Они записаны отдельно от client SEVERE и не объявлены безвредными только на основании Done.

Базовый Loader зарегистрировал 13 записей, включая Minecraft/Java/Fabric Loader и принадлежащий загрузчику MixinExtras 0.5.0. Фактический последующий библиотечный inventory сверяется с native runtime и исходными SHA; его проверенный снимок описан ниже. Server Done, статический mod inventory и обычный TCP не заменяют полный клиент или настоящую Steam-сессию. Личные игровые экземпляры и миры не использовались.

Оба финальных production окна сохранили те же восемь texture SEVERE и Realms недоступность. `Reached end of stream for /127.0.0.1` относится к намеренной отрицательной TCP spoof-проверке; `Socket closed` наблюдается при native stop и был в baseline. Ранний helper ввёл `MalformedURLException: no protocol:` из отсутствующего cape URL; это не baseline. Финальный helper `814347…` завершил свежую и повторную сессии без этой signature, без NPE и без fatal markers; логи не подавлялись. Неизвестных новых content/Mixin/renderer exceptions в этих временных окнах не обнаружено. Ранний `production-rename-08` был остановлен до Minecraft корректным QA guard отсутствующего reentry-path; после фактического Saves.Prepare путь восстановлен и native rename-09 прошёл. Ошибка stand не выдаётся за ошибку игры.

Финальный Loader зарегистрировал 15 записей. Из них исключены только Minecraft, Java, Fabric Loader и управляемый e4steam: библиотечный actual snapshot содержит 11 mod ID, включая MixinExtras. Native origin подтверждает `fabricloader!META-INF/jars/mixinextras-fabric-0.5.0.jar`; официальный Loader container SHA и вложенный SHA закреплены отдельно от source JAR hashes. Два реальных Steam-аккаунта и визуальный осмотр не выполнены, что соответствует доступному одному ПК и аккаунту; эти ограничения не превращаются в PASS.
