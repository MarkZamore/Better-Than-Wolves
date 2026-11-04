# Правила Better Than Wolves

- Платформа: Minecraft 1.6.4 / Legacy Fabric 0.18.2 / Java 17. Авторские CE 3.1.1 и Nightmare Mode 1.1.6 закреплены в upstream-lock и не заменяются скрытно. При обновлении каждого JAR повторить provenance/dependencies/sides/Safety проверку.
- Единственный источник видимого имени: `library/content.json:name`. Стабильные ID `better-than-wolves`, путь `Better Than Wolves`, GitHub source и mod IDs имеют отдельное назначение.
- Активные JAR, locks и inventory изменяет один владелец последовательно. Обычные restore/prepare/CI не обновляют pins. `--freeze` является явным действием подготовки, а не способом принять неизвестное изменение.
- Обновление автора выполняется через `tools/update_upstream.py` в новом независимом artifacts candidate. Обязательный overlay/receipt сохраняет descriptor, pack-owned и bridge. Конфликт author patch-site SHA или recipe drift останавливает подготовку до явного source/runtime review. Не импортировать новые JAR поверх активного источника и не снимать guards ради обновления.
- Сохранять авторские механики и личные настройки. Не вводить limits render/simulation/shadow distance, не выключать эффекты/анимации/guards ради старта. Игровой source и личный instance не являются QA стендом.
- Контентные изменения принадлежат сборке. Общая Legacy Fabric установка и Steam transport принадлежат лаунчеру/общему e4steam. Не добавлять правила по имени сборки в C# и не включать управляемый e4steam в исходный mods/счётчик.
- Manifest schema 2 требует `legacy-fabric-runtime-v1`, `legacy-player-profiles-v1`, `legacy-steam-v1`. Не снимать gates ради старого EXE. Публиковать совместимый EXE и общий транспорт до карточки.
- LAN доступен любому хосту. Dedicated и локальный TCP не являются настоящей Steam-сессией. Непроведённые проверки скинов/прогресса/смены хоста не объявлять PASS.
- Каждое новое world/inventory/native-permission поведение проверять на необходимость Safety. Не переносить чужую gameplay/claim policy без решения пользователя; неизвестный источник не считать разрешённым игроком.
- Библиотека покрывает все фактические mod IDs, включая nested. Проверять ссылки именно CE/fork/автора; для собственного bridge использовать официальный репозиторий и документировать исключение. Превью должно иметь точное происхождение.
- Текущий тестовый статус и ERROR вести в TESTING-STATUS.md. Сырые логи, личные paths/UUID/PNG/registry не публиковать. Payload публикуются до manifest; каталог обновляется только после независимой public-byte проверки.
