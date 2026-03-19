package com.hamradio.aaos.radio.protocol

/**
 * Command group constants (first 2 bytes of every message).
 */
object CommandGroup {
    const val BASIC:    Int = 2
    const val EXTENDED: Int = 10
}

/**
 * All known BASIC command IDs.
 */
object BasicCommand {
    const val GET_DEV_ID            = 1
    const val SET_REG_TIMES         = 2
    const val GET_REG_TIMES         = 3
    const val GET_DEV_INFO          = 4
    const val READ_STATUS           = 5
    const val REGISTER_NOTIFICATION = 6
    const val CANCEL_NOTIFICATION   = 7
    const val GET_NOTIFICATION      = 8
    const val EVENT_NOTIFICATION    = 9
    const val READ_SETTINGS         = 10
    const val WRITE_SETTINGS        = 11
    const val STORE_SETTINGS        = 12
    const val READ_RF_CH            = 13
    const val WRITE_RF_CH           = 14
    const val GET_IN_SCAN           = 15
    const val SET_IN_SCAN           = 16
    const val SET_REMOTE_DEVICE_ADDR= 17
    const val GET_TRUSTED_DEVICE    = 18
    const val DEL_TRUSTED_DEVICE    = 19
    const val GET_HT_STATUS         = 20
    const val SET_HT_ON_OFF         = 21
    const val GET_VOLUME            = 22
    const val SET_VOLUME            = 23
    const val RADIO_GET_STATUS      = 24
    const val RADIO_SET_MODE        = 25
    const val RADIO_SEEK_UP         = 26
    const val RADIO_SEEK_DOWN       = 27
    const val RADIO_SET_FREQ        = 28
    const val READ_ADVANCED_SETTINGS= 29
    const val WRITE_ADVANCED_SETTINGS=30
    const val HT_SEND_DATA          = 31
    const val SET_POSITION          = 32
    const val READ_BSS_SETTINGS     = 33
    const val WRITE_BSS_SETTINGS    = 34
    const val FREQ_MODE_SET_PAR     = 35
    const val FREQ_MODE_GET_STATUS  = 36
    const val READ_RDA1846S_AGC     = 37
    const val WRITE_RDA1846S_AGC    = 38
    const val READ_FREQ_RANGE       = 39
    const val WRITE_DE_EMPH_COEFFS  = 40
    const val STOP_RINGING          = 41
    const val SET_TX_TIME_LIMIT     = 42
    const val SET_IS_DIGITAL_SIGNAL = 43
    const val SET_HL                = 44
    const val SET_DID               = 45
    const val SET_IBA               = 46
    const val GET_IBA               = 47
    const val SET_TRUSTED_DEVICE_NAME=48
    const val SET_VOC               = 49
    const val GET_VOC               = 50
    const val SET_PHONE_STATUS      = 51
    const val READ_RF_STATUS        = 52
    const val PLAY_TONE             = 53
    const val GET_DID               = 54
    const val GET_PF                = 55
    const val SET_PF                = 56
    const val RX_DATA               = 57
    const val WRITE_REGION_CH       = 58
    const val WRITE_REGION_NAME     = 59
    const val SET_REGION            = 60
    const val SET_PP_ID             = 61
    const val GET_PP_ID             = 62
    const val READ_ADVANCED_SETTINGS2 =63
    const val WRITE_ADVANCED_SETTINGS2=64
    const val UNLOCK                = 65
    const val DO_PROG_FUNC          = 66
    const val SET_MSG               = 67
    const val GET_MSG               = 68
    const val BLE_CONN_PARAM        = 69
    const val SET_TIME              = 70
    const val SET_APRS_PATH         = 71
    const val GET_APRS_PATH         = 72
    const val READ_REGION_NAME      = 73
    const val SET_DEV_ID            = 74
    const val GET_PF_ACTIONS        = 75
    const val GET_POSITION          = 76
}

object ExtendedCommand {
    const val GET_BT_SIGNAL     = 769
    const val GET_DEV_STATE_VAR = 16387
    const val DEV_REGISTRATION  = 1825
}

object PowerStatusType {
    const val BATTERY_LEVEL      = 1
    const val BATTERY_VOLTAGE    = 2
    const val RC_BATTERY_LEVEL   = 3
    const val BATTERY_PERCENTAGE = 4
}
