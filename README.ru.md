<p align="center">
  <img src="src/main/resources/icons/cliq.svg" width="120" alt="Cliq AI Companion" />
</p>

<h1 align="center">Cliq AI Companion</h1>

<p align="center">
  Графическая панель управления для агентов Claude Code, Gemini CLI, Qwen Code — нативно встроенная в JetBrains IDE.
</p>

<p align="center">
  Cliq объединяет IDE, терминал и контекст проекта в единый рабочий процесс.<br/>
  Запускайте AI CLI-агентов в один клик, делитесь живым контекстом рабочего пространства через MCP,<br/>
  и просматривайте каждое предложенное изменение файлов как нативный IDE diff.
</p>

<p align="center">
  🌐 Languages: <a href="README.md">English</a> | <a href="README.ru.md">Русский</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-0.1.0-01B43C.svg" />
  <img src="https://img.shields.io/badge/JetBrains%20Platform-2024.3%2B-000000.svg" />
  <img src="https://img.shields.io/badge/since--build-243-blue.svg" />
  <img src="https://img.shields.io/badge/Kotlin-2.1.0-7F52FF.svg" />
  <img src="https://img.shields.io/badge/JVM-21-ED8B00.svg" />
  <img src="https://img.shields.io/badge/license-Apache--2.0-blue.svg" />
</p>

---

## Обзор

Claude Code, Gemini CLI, Qwen Code — мощные инструменты, но их использование внутри IDE часто требует постоянного переключения между терминалом, редактором и файлами проекта.

Cliq устраняет это трение. Он запускает агентов внутри терминала JetBrains, передаёт актуальный контекст рабочего пространства и превращает изменения, сгенерированные AI, в реальные IDE diff-изменения, которые можно просматривать, редактировать, принимать или отклонять.

Cliq не заменяет вашего агента и не включает модели. Он добавляет недостающий слой интеграции с IDE.

---

## Основные возможности

- **Запуск агента в один клик**  
  Запускайте Claude, Gemini, Qwen из tool window, панели инструментов, меню Tools или через `Ctrl+Alt+Q`.

- **Нативный просмотр изменений (IDE diff)**  
  Изменения от AI открываются как side-by-side diff с подсветкой синтаксиса, редактируемыми предложениями и действиями для каждого блока изменений.

- **Синхронизация контекста рабочего пространства в реальном времени**  
  Передача активных файлов, позиции курсора, выделения и недавно открытых файлов запущенному агенту.

- **Встроенный MCP сервер**  
  Локальный MCP сервер с loopback и безопасным доступом по токену.

- **Панель файлового контекста**  
  Закрепляйте файлы, отслеживайте недавно открытые и отправляйте ссылки `@file` напрямую в терминал.

- **Конструктор промптов**  
  Формируйте промпты с опциональным контекстом файлов и мгновенно отправляйте их в активную терминальную сессию.

- **Режим авто-применения**  
  Доверенные автономные запуски могут вносить изменения напрямую без ручного подтверждения.

- **Контекст Pull / Merge Request для ревью (GitHub и GitLab)**  
  Выберите открытый pull request в GitHub или merge request в GitLab (или введите его номер) — Cliq соберёт диффы, полные файлы, связанные тесты, статус CI, логи упавших джобов и обсуждения, разобьёт их на чанки по бюджету токенов и закрепит в панели Context вместе с готовым промптом для ревью. Поддерживаются GitHub Enterprise и self-managed GitLab. Хосты и токены настраиваются в `Settings → Tools → Cliq → Code Review`.

- **Без телеметрии**  
  Никакой аналитики и фоновых запросов. Cliq обращается только к настроенным хостам и только когда вы собираете контекст ревью.

---

## Установка

### JetBrains Marketplace

1. Откройте `Settings` → `Plugins` → `Marketplace`
2. Найдите `Cliq AI Companion`
3. Нажмите `Install`
4. Перезапустите IDE

### Поддерживаемые IDE

- IntelliJ IDEA
- WebStorm
- PhpStorm
- GoLand
- PyCharm
- RustRover
- RubyMine
- CLion
- DataGrip
- Android Studio (соответствующая версия платформы)

Требуется IntelliJ Platform `2024.3+`.

---

### Ручная установка

1. Скачайте последнюю версию ZIP из Releases
2. Откройте `Settings` → `Plugins`
3. Выберите `Install Plugin from Disk...`
4. Перезапустите IDE

---

## Требования

Cliq работает с внешними CLI-агентами и не поставляет их вместе с плагином.

Установите один или оба:

- Claude Code (`claude`)
- Gemini CLI (`gemini`)
- Qwen CLI (`qwen`)

Исполняемые файлы должны быть доступны через `PATH` или настроены вручную в Settings.

---

## Быстрый старт

### 1. Настройка агентов

Откройте:

`Settings → Tools → Cliq`

Укажите команды, например:

```bash id="p3m9qd"
claude
gemini
qwen
```