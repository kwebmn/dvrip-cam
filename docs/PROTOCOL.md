# DVRIP (Sofia/Xiongmai) — справочник протокола для приложения

Составлено по **живому опросу нашей камеры** (Hi3518EV200, LiteOS `...037507`, `admin`).
`✔` = проверено на этой камере, `±` = стандартно для DVRIP (не проверяли/не реализовано здесь).

---

## 1. Транспорт и логин

- TCP порт **34567**. Каждое сообщение: **20-байт заголовок + JSON-тело** (тело кончается `\n\0`).
- Заголовок (LE): `BB HH II II B B HH II` = `0xFF, 0, 0, SessionID(4), Sequence(4), 0, 0, MsgID(2), BodyLen(4)`.
- **Пароль** = sofia_hash: `md5(pass)` → по 2 байта → индекс в таблице `0-9A-Za-z` (62 симв) → 8 символов.
- Логин: MsgID **1000**, тело `{"EncryptType":"MD5","LoginType":"DVRIP-Web","UserName":"admin","PassWord":sofia_hash(pw)}`.
  Ответ `Ret=100` + `SessionID` (hex-строка). Дальше SessionID кладём в заголовок и в тело.
- Коды: `100`=OK, `102`=секция не поддержана, `103`=команда не поддержана, `106`=неверный пароль, `109`=?.

## 2. Опкоды (MsgID)

| MsgID | Команда | Назначение | Статус |
|---|---|---|---|
| 1000 | Login | вход | ✔ |
| 1006 | KeepAlive | держать сессию | ± |
| **1042** | ConfigGet | **читать секцию** (`{"Name":...}`) | ✔ |
| **1040** | ConfigSet | **писать секцию** (`{"Name":..., "<Name>":{...}}`) | ✔ |
| **1360** | GetAbility | способности (`{"Name":"SystemFunction"...}`) | ✔ |
| 1413 / 1410 | OPMonitor Claim / Start | **живой поток** (video+audio DHAV) | ✔ |
| **1424 / 1420** | OPPlayBack Claim / DownloadStart | **плейбэк/скачивание записи** (порядок важен!) | ✔ |
| **1440** | OPFileQuery | **список записей** на SD | ✔ |
| 1442 | OPLogQuery | системный лог | ± (пусто) |
| 1434 / 1436 | OPTalk Claim / данные | **двусторонний звук** (микрофон→камера) | ± |
| 1452 | OPTimeQuery | время устройства | ✔ |
| 1450 | OPTimeSetting | установить время | ± |
| 1472 | Users | список аккаунтов | ✔ |
| 1470 / 1466 / 1464 | AuthorityList / Groups / AddGroup | управление правами/группами | ± |
| 1400 | OPPTZControl | PTZ (у нас нет поворотки) | ± |
| 1560 | OPSNAP | снапшот-кадр | ✗ (не реализовано — таймаут) |
| 1450 | OPMachine / OPTimeSetting | reboot/shutdown/default; установка времени | ± (осторожно!) |
| 1430 | OPTalk **Start** | открыть динамик (после Claim 1434!) — talk-back | ± (из Icsee-android) |
| 1426 | OPPlayBack data | приходят медиа-данные плейбэка | ✔ |
| 1484 | ModifyUser | переименовать аккаунт | ± |
| 1660/1661 | GetRandomUser / ChangeRandomUser | восстановить/сменить пароль (AES, ключ из SerialNo), session-less | ± |
| 1460 | OPStorageManager | формат SD (Action:Clear) — деструктивно! | ± |
| 1020/1021 | SystemInfo / StorageInfo GET | инфо об устройстве/хранилище | ✔ |

> Детали из открытых исходников **voidnullvalue/Icsee-android** (MIT, greenfield-переписка iCSee).
> Важно: их камера отдаёт видео по **RTSP** (H.265), а DVRIP-поток «пустой»; **у нашей — наоборот**
> (RTSP нет, видео идёт через **OPMonitor 1413→1410**, H.264). Плейбэк у обоих одинаков: **1424→1420, данные 1426**.
> `OPFileQuery` требует `"Event":"*"` (иначе `Ret:119`). Talk-back: **Claim 1434 → Start 1430** → G711.

## 3. Что камера УМЕЕТ (секции конфига, `✔` = присутствуют)

### Сеть
- **NetWork.NetCommon** — IP/MAC/шлюз/маска/порты (HTTP 80, TCP 34567, UDP 34568, SSL 8443), MonMode, MaxBps.
- **NetWork.Wifi** — SSID/Keys/Auth/EncrypType/Channel → **настройка WiFi по сети** (см. отдельный разбор).
- **NetWork.NetDHCP** — DHCP по интерфейсам (eth0..3).
- **NetWork.NetDNS** — DNS-серверы.
- **NetWork.NetNTP** — NTP (сервер/таймзона/период) → синхронизация времени.
- **NetWork.NetIPFilter** — белый/чёрный список IP.
- **NetWork.Nat** — облако XMEye: `Addr=secu100.net`, `NatEnable` (**выключить = отвязать от облака**), порт 8000, DnsServer1/2.
- **NetWork.PMS** — пуш-сервис `push.umeye.cn` (`Enable` → выключить пуши).

### Видео/кодек
- **Simplify.Encode** — главный кодек: Main (1080P@25, 2048k, H.264), Extra (D1@20, 384k), Snap (1080P@1). BitRate/FPS/GOP/Quality/Resolution — **всё пишется**.
- **AVEnc.Encode / EncodeStaticParam** — расширенные параметры энкодера.
- **AVEnc.VideoColor** — яркость/контраст/насыщенность/оттенок по расписанию.
- **AVEnc.VideoWidget** — **OSD**: `ChannelTitle` (сейчас «Camera1»), позиция времени/титула (RelativePos 0-8192), **Covers = приватные маски (4 зоны)**, цвета/прозрачность.
- **Camera.Param / ParamEx / ClearFog** — ISP: экспозиция, день/ночь, шумодав, WDR, антитуман.
  Реально прочитанные поля (проверено на камере, get 1042 / set 1040, read-modify-write массива `[0]`):
  - `Camera.Param[0]`: `PictureMirror`/`PictureFlip`/`BLCMode`/`DayNightColor` (hex-строки "0x00000000"),
    `RejectFlicker` (int 0=выкл/1=50Гц/2=60Гц), `DncThr`, `IRCUTMode`, `AeSensitivity`, `WhiteBalance`.
  - `Camera.ParamEx[0]`: `CorridorMode`, `Dis` (стабилизация), `Ldc`, `LowLuxMode`, `BroadTrends` (WDR) — **int 0/1**.
  → В приложении раздел «Изображение»: зеркало/переворот/BLC/день-ночь/антимерцание/коридор/DIS/LowLux.
- **EncodeCapability** (ability) — маски поддерживаемых разрешений/кодеков (ResolutionMask `0xCBEFFFFF` для Main).

### Детекция и тревоги
- **Detect.MotionDetect** — детектор движения: зоны (MotionArea grid), чувствительность, расписание (TimeSection), действия (Snap/Record/Message/Beep/PTZ-link).
- **Detect.BlindDetect / LossDetect** — засветка/потеря видео.
- **Detect.HumanDetection** — детекция человека (Enable).
- **Alarm.PIR** — PIR-датчик: чувствительность, PIRCheckTime, расписание, действия (Snap/Record/Msg).
- **Alarm.LocalAlarm / NetAbort** — локальные тревоги / обрыв сети.
- **MotionArea** (ability) — сетка зон движения (GridRow/GridColumn битмаски).

### Запись/хранилище
- **Record** — расписание записи (Mask по дням/часам, RecordMode ConfigRecord, PreRecord 5с, PacketLength 3мин).
- **Storage** — раскладка папок на SD (video/photo/time_lapse), объекты записи, статусы (NotExist/LowSpace/Failure).
- **Storage.Snapshot** — расписание снапшотов (SnapMode, PreSnap).
- **StorageGlobal** — KeyOverWrite (перезапись при заполнении).

### Аудио
- **TalkAudioFormat** (ability) — двусторонний звук: **G711_ALAW, 8кГц, 16 бит, 128к** (для talk-back в приложении).
- В потоке OPMonitor аудио идёт как PCMA/8000.

### Система
- **General.General** — имя устройства, автологаут, язык, OverWrite.
- **General.Location** — дата/формат, летнее время (DST), таймзона.
- **General.AutoMaintain** — автоперезагрузка по расписанию, автоудаление старых файлов.
- **WorkMode** — CapturePriority (режим батарейной камеры).
- **Users** — аккаунты. Сейчас один: `admin`, группа admin, **28 прав** (ShutDown, RecordConfig, StorageManager, Account, SysUpgrade, ImExport, PTZControl, Talk_01, Monitor_01, Replay_01, …). Пароль-хэш `<hash-redacted>`.
- **OPTimeQuery/Setting** — чтение/установка времени.

## 4. Чего камера НЕ умеет (в этой прошивке `Ret=102/103`)

**RTSP, ONVIF, Telnet, UPnP, Email(SMTP), FTP, DDNS, PPPoE, SNMP, Multicast, HTTPS, 3G/Mobile, PTZ, OPSNAP, WiFi-скан.**
→ Поэтому для RTSP нужен мост (go2rtc), а WiFi-сети приложение спрашивает у пользователя (камера список не отдаёт).

## 5. Что это даёт приложению (готовый набор функций)

1. **Живое видео + звук** — OPMonitor (1413→1410), Main/Extra поток.
2. **Архив с SD** — OPFileQuery (1440) список + OPPlayBack (1424→1420) скачивание/просмотр с перемоткой.
3. **Двусторонний звук** — OPTalk (Claim 1434 → Start 1430), аудио телефон→камера на **1432** (проверено: 1436=тишина), G711.
4. **Настройка WiFi** — SET NetWork.Wifi (онбординг через SoftAP `camera_`/`1234567890`).
5. **Отвязка от Китая** — SET NetWork.Nat `NatEnable=false` + NetWork.PMS `Enable=false`.
6. **Кодек/качество** — SET Simplify.Encode (разрешение/FPS/битрейт).
7. **OSD и приватные маски** — SET AVEnc.VideoWidget.
8. **Детекция/PIR/запись** — SET Detect.*/Alarm.PIR/Record (зоны, чувствительность, расписание, действия).
9. **Время** — OPTimeSetting + NTP.
10. **Пользователи** — Users/Groups/Authority (сменить пароль, добавить юзера).
11. **Поиск камеры в сети** — слушать UDP broadcast **34569** (камера сама шлёт NetCommon-JSON).
12. **Изображение** — GET/SET Camera.Param + Camera.ParamEx (зеркало/переворот/день-ночь/антимерцание/BLC/коридор/DIS/LowLux).
13. **Перезагрузка** — OPMachine (1450) `{"Action":"Reboot"}`.
14. **Журнал событий** — OPLogQuery (**1442**, Ret 100 подтверждён; формат запроса требует Type/BeginTime/EndTime).

> Полный справочник опкодов, ~120 config-секций и Ret-кодов (из дизасма XMEye 1.6.2.9) — в `/root/camera/XMEYE-DEX-REFERENCE.md`.

## 6. Важные оговорки для приложения
- **Батарейный сон**: перед любой операцией камеру надо разбудить (PIR/питание; сетевой побудки нет). В SoftAP-режиме (онбординг) она бодрствует.
- **Плейбэк с SD ~5 Мбит/с** — большие клипы качаются небыстро (лучше потоковая отдача).
- **Один heavy-канал за раз** — сериализуй тяжёлые операции (плейбэк/поток), TCPMaxConn=10.
