# ForestNavigator / ЛесНавигатор

Android-приложение для автономной навигации в лесу. Координаты получает через GNSS/GPS, может заранее скачать область карты и использовать её без мобильной сети.

## Реализовано

- спутниковое позиционирование через `LocationManager.GPS_PROVIDER` (без обязательной зависимости от Google Play Services);
- сохранение точки машины;
- сохранение грибных мест и других точек;
- режим «точная точка»: серия GNSS-фиксов, фильтрация плохих измерений и взвешенное усреднение;
- отображение реальной оценочной точности точки (приложение не обещает физически невозможные 1 м, если телефон их не обеспечивает);
- компас через Rotation Vector, с резервом на акселерометр + магнитометр;
- диагностика GNSS, магнитометра, акселерометра, гироскопа, барометра и датчика приближения;
- навигация к сохранённой точке: азимут, направление, расстояние;
- запись GPS-трека в foreground service;
- локальная SQLite-база для точек и треков;
- базовая карта OpenFreeMap;
- слои MapTiler Satellite и Landscape/relief при наличии собственного API key;
- пользовательский MapLibre Style URL;
- загрузка области 2/5/10 км вокруг текущей позиции через MapLibre Offline Region;
- адаптивный интерфейс Compose: нижняя навигация на узком экране, NavigationRail на широком; карта и панели занимают отдельные зоны, поэтому элементы управления не перекрывают друг друга;
- safe drawing insets, edge-to-edge без перекрытия системными панелями;
- портретная/альбомная ориентация, планшеты и foldable-friendly компоновка;
- Vulkan + OpenGL runtime fallback MapLibre;
- GitHub Actions сборка APK.

## Энергопотребление

Приложение разделяет режимы GNSS:

- ECO: 10 сек / 5 м;
- NORMAL: 3 сек / 2 м;
- PRECISION: 1 сек / 0 м только на время уточнения точки.

Компас обновляет UI примерно до 10 Гц. GPS foreground Activity прекращается при уходе приложения с экрана. При записи маршрута работает отдельный foreground service с постоянным системным уведомлением.

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

Обычный телефон не может гарантировать 1 м в лесу. Под кронами ошибка GNSS часто возрастает. Режим точной точки собирает несколько спутниковых измерений и сохраняет вместе с координатой фактическую оценку точности. Если устройство даёт ±1–2 м, это будет видно; если оно даёт ±6 м, приложение не будет показывать фиктивный «1 м».

## GitHub Actions

Workflow: `.github/workflows/android-build.yml`

Ручная сборка:

1. GitHub → **Actions** → **Android Build**.
2. **Run workflow**.
3. После успешной сборки открыть run → **Artifacts**.
4. Скачать `ForestNavigator-debug-apk`.

Workflow использует JDK 17, Android SDK API 37, Build Tools 36.0.0, Gradle 9.6.0 и собирает `:app:assembleDebug`.

## Технологии

- Kotlin 2.4.20
- Android Gradle Plugin 9.4.0
- Jetpack Compose BOM 2026.09.00
- MapLibre Native Android 13.6.1
- minSdk 26
- targetSdk 37
- compileSdk 37

## Разрешения

- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_LOCATION`
- `POST_NOTIFICATIONS` (Android 13+)
- `INTERNET` и `ACCESS_NETWORK_STATE` — только для скачивания карт/онлайн-показа до выхода в лес.

`ACCESS_BACKGROUND_LOCATION` намеренно не запрашивается: длительная запись запускается пользователем из видимого приложения через location foreground service.
