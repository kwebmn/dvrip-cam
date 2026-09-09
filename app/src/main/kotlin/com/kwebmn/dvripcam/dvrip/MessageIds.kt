package com.kwebmn.dvripcam.dvrip

/** Опкоды DVRIP (MsgID). См. docs/PROTOCOL.md. */
object MessageIds {
    const val LOGIN = 1000
    const val KEEPALIVE = 1006
    const val SYSTEM_INFO = 1020
    const val CONFIG_SET = 1040
    const val CONFIG_GET = 1042
    const val ABILITY_GET = 1360
    const val MONITOR_START = 1410
    const val MONITOR_CLAIM = 1413
    const val PLAYBACK_DOWNLOAD_START = 1420
    const val PLAYBACK_CLAIM = 1424
    const val PLAYBACK_DATA = 1426
    const val TALK_START = 1430
    const val TALK_CLAIM = 1434
    const val FILE_QUERY = 1440
    const val TIME_SETTING = 1450
    const val TIME_QUERY = 1452
    const val USERS = 1472
}
