# Better Than Wolves для LANMinecraft

Minecraft Java 1.6.4, Legacy Fabric Loader 0.18.2, Java 17; Better Than Wolves: Community Edition 3.1.1 и Nightmare Mode 1.1.6. Авторские игровые JAR загружены из указанных пользователем официальных Modrinth версий без замен и изменений. Стабильный ID `better-than-wolves`, путь установки `Better Than Wolves`; редактируемое видимое имя находится только в `library/content.json:name`.

## Источники и воспроизводимость

Better Than Wolves CE продолжает Better Than Wolves FlowerChild; авторы CE указаны в исходном JAR и на https://modrinth.com/mod/btwce. Nightmare Mode: elmi2305, https://modrinth.com/mod/nightmare-mode-btw. Точные project/version IDs, официальные URL, размеры, SHA256 и официальные SHA512/SHA1 закреплены в `tools/upstream-lock.json`. Дополнительные библиотеки уже вложены авторами; скрытых обновлений или замен нет.

`tools/distribution-lock.json` описывает весь активный игровой состав, отдельно от источников и исторических версий. Подготовка скачивает только отсутствующие exact-hash файлы и отказывается перезаписывать изменённый авторский JAR. Новые версии требуют явного пересмотра pins и полного QA. Игровые файлы восстанавливаются из чистого checkout:

Для воспроизведения собственного bridge требуется отдельный компилятор **Eclipse Temurin 21.0.12.1+1**, `JAVA_HOME` указывает на него; target остаётся Java 17 (`javac --release 17`). Это не изменение Java-версии игры. CI закрепляет тот же compiler, чтобы не предполагать одинаковые bytes разных javac. Точный selector setup-java записан как `21.0.12+101.0.LTS`: это SemVer-представление именно релиза `21.0.12.1+1` в официальном API Adoptium, а не другой JDK.

```text
python -B tools/restore_distribution.py --pack .
python -B tools/restore_distribution.py --pack . --verify-only
python -B -m unittest discover -s tools -p "test_*.py"
python -B tools/keybinds/check_preset.py
python -B tools/generate_manifest.py --pack . --out dist --revision <full-commit-sha>
python -B tools/generate_manifest.py --pack . --out dist --verify-only
```

Необязательный `--cache artifacts/downloads` у restore/prepare использует content-addressed файлы `<cache>/<sha256>`. Каждый вход сверяется по размеру и SHA256 до копирования, повреждённый кеш не принимается. Для компиляции bridge отдельно проверяются все закреплённые зависимости; готовый helper не подменяет сборку из исходников.

Явное обновление активного lock после согласованного изменения выполняет один владелец. Только после финальной runtime-проверки bridge он записывает `python -B tools/update_upstream.py --pack . --record-reviewed-overlay`, затем `python -B tools/prepare_pack.py --pack . --freeze`. Первая команда фиксирует exact-hash результата и всех исходников рецепта, а не выполняет игровую проверку. Обычное восстановление и CI pins не обновляют. Исходные личные экземпляры, миры, `options.txt`, скриншоты и пользовательские журналы не входят в поставку. Собственные ограничения дальности, симуляции, теней, качества, эффектов или авторских механик не вводятся.

## Подготовка обновления автора

Обязательный слой поставки определён в `tools/overlay.json`: runtime descriptor, явный `pack-owned`, [пресет управления](tools/keybinds/design.md) и технический bridge. `tools/overlay-receipt.json` связывает точный upstream lock, определение overlay и применённые операции. Действующий bridge 1.0.0 имеет SHA256 `16765c1b038b23dccb64ca1f968a4116930e818aa520f5197f0eb94e6c47cb5a` и размер 23 334 байта. Он сохраняет выбранные имена в объявлениях о достижениях CE и Nightmare и наследует Creative для гостя при открытии Creative-мира. Для проверки через настоящий клиент использован независимый QA-мир; границы сетевой проверки указаны в `TESTING-STATUS.md`. Его исходники и exact-SHA targets принадлежат сборке, а не лаунчеру.

Новый author-input задаётся отдельным явным upstream lock с официальными HTTPS-источниками, размерами и SHA256. Инструмент готовит только новый независимый candidate:

```text
python -B tools/update_upstream.py --pack . --upstream-lock artifacts/reviewed-new-upstream.json --out artifacts/candidate-new
```

Неизменённые exact-hash входы повторно используются копированием; новые скачиваются и проверяются. Обязательный overlay переносится после авторских входов и получает новую квитанцию. Авторский конфликт с descriptor/ownership/bridge, пропавший helper, изменение рецепта или exact-SHA guarded author JAR останавливают подготовку; действующий источник и его locks не изменяются. Новая версия CE/Nightmare требует отдельного пересмотра helper caller/sites и guards, а не автоматического принятия через `--freeze`. Несвязанные авторские ресурсы могут изменяться без потери нашего слоя. Снимок ModList удаляется из candidate как устаревший и снимается заново после runtime. Положительный synthetic update-test не выдаётся за фактическое обновление автора или Minecraft PASS.

## Лаунчер и сетевая игра

Поддержка Legacy Fabric требует общих возможностей лаунчера `legacy-fabric-runtime-v1`, `legacy-player-profiles-v1` и `legacy-steam-v1`, заявленных manifest schema 2. Старый EXE обязан предложить обновление до установки/запуска. Общий транспорт e4steam принадлежит отдельному https://github.com/MarkZamore/e4steam и выбирается лаунчером по loader/Minecraft из единого проверенного startup snapshot. Он не копируется в исходный `mods` этой сборки, не входит в её modCount и не скачивается инструментами контентной подготовки.

Любой участник может открыть встроенный сервер и стать LAN-хостом. Dedicated является вспомогательным QA, а не назначенной пользователю ролью. Реальные Steam discovery/P2P/authentication, локальный TCP, нативная публикация сервера и офлайн preflight имеют разные границы доказательства. Стабильность профиля, скины и сохранение прогресса после смены хоста необходимо проверять адресно; непроведённые сценарии не являются PASS.

Конфиги, ресурсы и технические исправления контента принадлежат сборке. Технический bridge документируется в `tools/legacy-bridge`; он не разрешает скрытые изменения механик CE/Nightmare. При стабильных canonical storage names он адресно сохраняет выявленные авторские эффекты выбранных имён Notch, deadmau5, Dinnerbone и Grumm, не переписывая username/session или глобальные name getters. Общие loader/runtime/Steam инфраструктура находятся в лаунчере и e4steam. Контентных исключений, применяемых лаунчером по имени сборки или мода, нет. Статусы и оставшиеся ошибки находятся в `TESTING-STATUS.md`, границы Safety в `SAFETY.md`.

Фактический ModList фиксируется после native QA командой `python -B tools/record_runtime_inventory.py --pack . --evidence <private-evidence-directory>`. Она сверяет реально запущенные SHA исходных JAR и вложенное происхождение MixinExtras. В библиотеке 11 модов с техническими компонентами; Minecraft/Java/Loader и отдельно управляемый e4steam исключены. Private evidence и скин в репозиторий не копируются. Проверена публикация собственного Steam-мира на одном аккаунте и одном ПК; соединение двух реальных Steam-участников и смена их ролей не объявлены выполненными.

Весь специфичный для этой сборки acceptance stand находится в `tools/runtime-qa`, включая C# host в `tools/runtime-qa/host`. Он использует production services через обязательный внешний `LauncherProject`, без личного workspace path и без копирования реализации лаунчера. Перенос QA host не меняет игровые payload или принятые runtime hashes.

## Публикация

Workflow восстанавливает и проверяет закреплённую поставку, запускает Python tests и сначала полностью скачивает публичный совместимый лаунчер `latest` (релиз не ниже 445), `update.json` и все объявленные EXE/delta assets. Проверяются реальные byte counts, SHA256 и GitHub server digests, а также неизменность public snapshot во время загрузки. Новые версии выше 445 принимаются; сама поддержка функций по-прежнему определяется тремя capabilities манифеста, а не одним номером. Этот отчёт проверяет доставку, не заменяет Defender или runtime acceptance.

Затем публикуются content-addressed payload перед authoritative `pack-manifest.json` в rolling release `pack-latest`. После публикации отдельный процесс на чистом GitHub runner заново скачивает **все** публичные assets сборки и проверяет revision, hashes, archive members и библиотеку. Это настоящий повторный public download на runner; проверка API digest без полного тела не выдаётся за него. На медленном локальном соединении такой результат не называется локальным повторным скачиванием.

Обе проверки доступны вручную при подходящем соединении:

```text
python -B tools/verify_launcher_delivery.py --out artifacts/launcher-public-<revision> --minimum-release 445
python -B tools/verify_published.py --pack . --out artifacts/public-<revision> --revision <full-commit-sha>
```

Команда требует новый выходной каталог, сверяет все assets/revision/SHA/ZIP members/metadata/library. Только после успешной проверки добавляется `tools/catalog-entry.json` в актуальный GitHub каталог LANMinecraft. Карточка сама по себе не требует выпуска EXE, но для новой платформы сначала должен выйти совместимый общий лаунчер. Изображение и официальные ссылки документированы в `library/README.md`.

CI сохраняет artifact `btw-public-delivery-<source-sha>` только с ограниченными публичными JSON-отчётами, launcher update pointer, pack manifest, library metadata и preview. EXE, дельты, игровые JAR, личные registry/скины и raw logs туда не входят. `collect_public_proof.py` ограничивает размеры и извлекает только два заранее известных library members. Финальная проверенная source revision и фактические public-byte результаты записываются также в release notes; этот документ и `TESTING-STATUS.md` остаются снимком подготовки, без бесконечного изменения payload revision ради описания завершённой публикации.
