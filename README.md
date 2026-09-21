# ForestNavigator / ЛесНавигатор

Android-приложение для автономной навигации в лесу. Координаты получает через GNSS/GPS, может заранее скачать область карты и использовать её без мобильной сети.

## Реализовано

- спутниковое позиционирование через `LocationManager.GPS_PROVIDER` без обязательной зависимости от Google Play Services;
- сохранение точки машины;
- сохранение грибных мест и других точек;
- режим «точная точка»: серия GNSS-фиксов, фильтрация плохих измерений и взвешенное усреднение;
- сохранение фактической оценочной точности вместо фиктивного обещания 1 м;
- компас через Rotation Vector, с резервом на акселерометр + магнитометр;
- диагностика GNSS, магнитометра, акселерометра, гироскопа, барометра и датчика приближения;
- навигация к сохранённой точке: азимут, направление и расстояние;
- запись GPS-трека в foreground service;
- локальная SQLite-база для точек и треков;
- базовая карта OpenFreeMap;
- слои MapTiler Satellite и Landscape/relief при наличии собственного API key;
- пользовательский MapLibre Style URL;
- загрузка области 2/5/10 км вокруг текущей позиции через MapLibre Offline Region;
- адаптивный интерфейс Compose: нижняя навигация на узком экране, NavigationRail на широком;
- карта и панели занимают отдельные зоны и не должны перекрывать друг друга;
- safe drawing insets и edge-to-edge без перекрытия системными панелями;
- портретная/альбомная ориентация, планшеты и foldable-friendly компоновка;
- Vulkan + OpenGL runtime fallback MapLibre;
- GitHub Actions сборка APK.

## Энергопотребление

Приложение разделяет режимы GNSS:

- ECO: 10 сек / 5 м;
- NORMAL: 3 сек / 2 м;
- PRECISION: 1 сек / 0 м только на время уточнения точки.

Компас ограничивает обновление интерфейса примерно до 10 Гц. GPS foreground Activity прекращается при уходе приложения с экрана. При записи маршрута используется отдельный location foreground service с постоянным системным уведомлением.

## Карты

### Базовая

По умолчанию используется OpenFreeMap Liberty:

`https://tiles.openfreemap.org/styles/liberty`

### Спутник и рельеф

Для MapTiler ключ вводится прямо в приложении: **Ещё → Настройки**.

- Satellite: `satellite-v4`
- Relief/Landscape: `landscape-v4`

Ключ намеренно не хранится в GitHub-репозитории.

## О точности 1 метр

Обычный смартфон не может гарантировать точность 1 м, особенно под кронами. Режим точной точки собирает несколько спутниковых измерений и сохраняет координаты вместе с фактической оценкой точности. Если устройство в конкретном месте получает ±1–2 м, это будет видно; если оно получает ±6 м, приложение не показывает фиктивный «1 м».

## GitHub Actions

Workflow: `.github/workflows/android-build.yml`

1. GitHub → **Actions** → **Android Build**.
2. **Run workflow**.
3. После успешной сборки открыть run → **Artifacts**.
4. Скачать `ForestNavigator-debug-apk`.

Проверенная CI-конфигурация использует JDK 17, Android SDK Platform 37.0, Build Tools 37.0.0 и Gradle 9.6.0. Debug APK успешно собран workflow run #6.

## Технологии

- Kotlin 2.4.20
- Android Gradle Plugin 9.4.0
- Jetpack Compose BOM 2026.09.00
- MapLibre Native Android 13.6.1
- minSdk 26
- targetSdk 36
- compileSdk 37

`compileSdk 37` нужен актуальным AndroidX/Compose-библиотекам; `targetSdk 36` оставлен на стабильном Android 16 runtime behavior.

## Разрешения

- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_LOCATION`
- `POST_NOTIFICATIONS` (Android 13+)
- `INTERNET` и `ACCESS_NETWORK_STATE` — для скачивания карт и онлайн-показа до выхода в лес.

`ACCESS_BACKGROUND_LOCATION` намеренно не запрашивается: длительная запись запускается пользователем из видимого приложения через location foreground service.
