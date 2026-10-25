package fi.digable.braviaremote.protocol

/**
 * Remote-control keys the app can send over IRCC-IP.
 *
 * [irccName] is the key name as the TV reports it from `system.getRemoteControllerInfo`;
 * [defaultCode] is the community-documented IRCC code used until the TV's own table has been
 * fetched (see [IrccCodeTable]). Codes are base64 strings the TV decodes into an IR frame.
 */
enum class RemoteKey(val irccName: String, val defaultCode: String) {
    POWER("TvPower", "AAAAAQAAAAEAAAAVAw=="),
    POWER_OFF("PowerOff", "AAAAAQAAAAEAAAAvAw=="),
    WAKE_UP("WakeUp", "AAAAAQAAAAEAAAAuAw=="),
    VOLUME_UP("VolumeUp", "AAAAAQAAAAEAAAASAw=="),
    VOLUME_DOWN("VolumeDown", "AAAAAQAAAAEAAAATAw=="),
    MUTE("Mute", "AAAAAQAAAAEAAAAUAw=="),
    CHANNEL_UP("ChannelUp", "AAAAAQAAAAEAAAAQAw=="),
    CHANNEL_DOWN("ChannelDown", "AAAAAQAAAAEAAAARAw=="),
    UP("Up", "AAAAAQAAAAEAAAB0Aw=="),
    DOWN("Down", "AAAAAQAAAAEAAAB1Aw=="),
    LEFT("Left", "AAAAAQAAAAEAAAA0Aw=="),
    RIGHT("Right", "AAAAAQAAAAEAAAAzAw=="),
    CONFIRM("Confirm", "AAAAAQAAAAEAAABlAw=="),
    RETURN("Return", "AAAAAgAAAJcAAAAjAw=="),
    HOME("Home", "AAAAAQAAAAEAAABgAw=="),
    OPTIONS("Options", "AAAAAgAAAJcAAAA2Aw=="),
    ACTION_MENU("ActionMenu", "AAAAAgAAAMQAAABLAw=="),
    DISPLAY("Display", "AAAAAQAAAAEAAAA6Aw=="),
    INPUT("Input", "AAAAAQAAAAEAAAAlAw=="),
    EXIT("Exit", "AAAAAQAAAAEAAABjAw=="),
    GUIDE("GGuide", "AAAAAQAAAAEAAAAOAw=="),
    TV("Tv", "AAAAAQAAAAEAAAAkAw=="),
    PLAY("Play", "AAAAAgAAAJcAAAAaAw=="),
    PAUSE("Pause", "AAAAAgAAAJcAAAAZAw=="),
    STOP("Stop", "AAAAAgAAAJcAAAAYAw=="),
    REWIND("Rewind", "AAAAAgAAAJcAAAAbAw=="),
    FORWARD("Forward", "AAAAAgAAAJcAAAAcAw=="),
    PREV("Prev", "AAAAAgAAAJcAAAA8Aw=="),
    NEXT("Next", "AAAAAgAAAJcAAAA9Aw=="),
    NUM1("Num1", "AAAAAQAAAAEAAAAAAw=="),
    NUM2("Num2", "AAAAAQAAAAEAAAABAw=="),
    NUM3("Num3", "AAAAAQAAAAEAAAACAw=="),
    NUM4("Num4", "AAAAAQAAAAEAAAADAw=="),
    NUM5("Num5", "AAAAAQAAAAEAAAAEAw=="),
    NUM6("Num6", "AAAAAQAAAAEAAAAFAw=="),
    NUM7("Num7", "AAAAAQAAAAEAAAAGAw=="),
    NUM8("Num8", "AAAAAQAAAAEAAAAHAw=="),
    NUM9("Num9", "AAAAAQAAAAEAAAAIAw=="),
    NUM0("Num0", "AAAAAQAAAAEAAAAJAw=="),
    RED("Red", "AAAAAgAAAJcAAAAlAw=="),
    GREEN("Green", "AAAAAgAAAJcAAAAmAw=="),
    YELLOW("Yellow", "AAAAAgAAAJcAAAAnAw=="),
    BLUE("Blue", "AAAAAgAAAJcAAAAkAw=="),
    SUBTITLE("Subtitle", "AAAAAgAAAJcAAAAoAw=="),
    AUDIO("Audio", "AAAAAQAAAAEAAAAXAw=="),
    TELETEXT("Teletext", "AAAAAQAAAAEAAAA/Aw=="),
    NETFLIX("Netflix", "AAAAAgAAABoAAAB8Aw=="),
    SLEEP_TIMER("SleepTimer", "AAAAAQAAAAEAAAA2Aw=="),
    PICTURE_OFF("PicOff", "AAAAAQAAAAEAAAA+Aw=="),
    HDMI1("Hdmi1", "AAAAAgAAABoAAABaAw=="),
    HDMI2("Hdmi2", "AAAAAgAAABoAAABbAw=="),
    HDMI3("Hdmi3", "AAAAAgAAABoAAABcAw=="),
    HDMI4("Hdmi4", "AAAAAgAAABoAAABdAw==");

    val digit: Int?
        get() = when (this) {
            NUM0 -> 0; NUM1 -> 1; NUM2 -> 2; NUM3 -> 3; NUM4 -> 4
            NUM5 -> 5; NUM6 -> 6; NUM7 -> 7; NUM8 -> 8; NUM9 -> 9
            else -> null
        }

    /**
     * The Android key code for the Android TV Remote channel, or null for keys that stay on IRCC.
     * Only keys checked on the KD-65XF9005 move over. A matching Android key code is not enough:
     * GUIDE (172) arrives there as KEYCODE_UNKNOWN, so Guide stays on IRCC. Power also has to work
     * in standby, when the channel is down.
     */
    val atvKeyCode: Int?
        get() = when (this) {
            VOLUME_UP -> 24; VOLUME_DOWN -> 25
            UP -> 19; DOWN -> 20; LEFT -> 21; RIGHT -> 22; CONFIRM -> 23
            RETURN -> 4; HOME -> 3
            CHANNEL_UP -> 166; CHANNEL_DOWN -> 167
            else -> null
        }

    companion object {
        val digits: List<RemoteKey> = listOf(NUM1, NUM2, NUM3, NUM4, NUM5, NUM6, NUM7, NUM8, NUM9, NUM0)
    }
}

/**
 * Maps [RemoteKey]s to the IRCC codes a particular TV understands.
 *
 * Starts from the built-in defaults and can be refined with the table the TV returns from
 * `system.getRemoteControllerInfo`, which is authoritative for that model.
 */
class IrccCodeTable(private val overrides: Map<String, String> = emptyMap()) {
    private val codes: Map<RemoteKey, String> =
        RemoteKey.entries.associateWith { key -> overrides[key.irccName] ?: key.defaultCode }

    fun codeFor(key: RemoteKey): String = codes.getValue(key)

    /** True when the TV reported its own code for [key] rather than the built-in default. */
    fun isFromTv(key: RemoteKey): Boolean = key.irccName in overrides

    companion object {
        val DEFAULT = IrccCodeTable()
    }
}
