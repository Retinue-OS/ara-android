package os.retinue.ara.keyboard

/**
 * The HID side of "Keyboard for other devices": the report descriptor Ara
 * registers with Android's BluetoothHidDevice, and the 8-byte boot-keyboard
 * reports it sends. Pure, so it is pinned down by unit tests.
 *
 * A report is `[modifiers, 0, key1, 0, 0, 0, 0, 0]`: one key at a time is
 * enough for typing, and the paced sender ([KeyPacer]) releases every key
 * before the next one, so a host never sees two keys down at once and a
 * repeated letter ("ll") arrives as two presses.
 */
object HidKeys {

    /** The report id Ara's descriptor gives the keyboard report. */
    const val REPORT_ID = 1

    // Modifier bits (byte 0 of a report).
    const val MOD_NONE = 0x00
    const val MOD_LCTRL = 0x01
    const val MOD_LSHIFT = 0x02
    const val MOD_LALT = 0x04
    const val MOD_RALT = 0x40 // AltGr on European layouts

    // Usage IDs (HID Usage Tables, Keyboard/Keypad page 0x07) used by name.
    const val KEY_A = 0x04
    const val KEY_1 = 0x1E
    const val KEY_0 = 0x27
    const val KEY_ENTER = 0x28
    const val KEY_ESCAPE = 0x29
    const val KEY_BACKSPACE = 0x2A
    const val KEY_TAB = 0x2B
    const val KEY_SPACE = 0x2C
    const val KEY_MINUS = 0x2D
    const val KEY_EQUAL = 0x2E
    const val KEY_LEFTBRACE = 0x2F
    const val KEY_RIGHTBRACE = 0x30
    const val KEY_BACKSLASH = 0x31
    const val KEY_NONUS_HASH = 0x32
    const val KEY_SEMICOLON = 0x33
    const val KEY_APOSTROPHE = 0x34
    const val KEY_GRAVE = 0x35
    const val KEY_COMMA = 0x36
    const val KEY_DOT = 0x37
    const val KEY_SLASH = 0x38
    const val KEY_CAPSLOCK = 0x39
    const val KEY_HOME = 0x4A
    const val KEY_DELETE = 0x4C
    const val KEY_END = 0x4D
    const val KEY_RIGHT = 0x4F
    const val KEY_LEFT = 0x50
    const val KEY_DOWN = 0x51
    const val KEY_UP = 0x52

    /** The key left of Y/Z on ISO keyboards (`<>` on German layouts). */
    const val KEY_102ND = 0x64

    /** The named keys the page may press (`RetinueShell.keyboardKey(name)`). */
    val NAMED: Map<String, Int> = mapOf(
        "enter" to KEY_ENTER,
        "tab" to KEY_TAB,
        "backspace" to KEY_BACKSPACE,
        "escape" to KEY_ESCAPE,
        "up" to KEY_UP,
        "down" to KEY_DOWN,
        "left" to KEY_LEFT,
        "right" to KEY_RIGHT,
        "home" to KEY_HOME,
        "end" to KEY_END,
        "delete" to KEY_DELETE,
        "space" to KEY_SPACE,
    )

    /** One key press: the modifiers held and the key. */
    data class Stroke(val modifiers: Int, val key: Int) {
        init {
            require(modifiers in 0..0xFF) { "modifiers" }
            require(key in 0..0xFF) { "key" }
        }
    }

    /** The report for [stroke] held down. */
    fun press(stroke: Stroke): ByteArray =
        byteArrayOf(stroke.modifiers.toByte(), 0, stroke.key.toByte(), 0, 0, 0, 0, 0)

    /** All keys up. */
    fun release(): ByteArray = ByteArray(8)

    /** The LED bit the host sets while its Caps Lock is on (HID LED page, usage 2). */
    const val LED_CAPS_LOCK = 0x02

    /**
     * The LED byte of an output report the host sent (SET_REPORT or interrupt
     * data), with or without the report id in front, or null for none.
     */
    fun ledByte(data: ByteArray?, reportId: Int = REPORT_ID): Byte? = when {
        data == null || data.isEmpty() -> null
        data.size >= 2 && data[0].toInt() == reportId -> data[1]
        else -> data[0]
    }

    fun capsLockOn(leds: Byte): Boolean = (leds.toInt() and LED_CAPS_LOCK) != 0

    /**
     * [strokes] typed with the host's Caps Lock out of the way: it would flip
     * every letter (and on some layouts more), so it is pressed off before
     * and on again after — layout-independent, unlike flipping Shift.
     */
    fun aroundCapsLock(strokes: List<Stroke>, capsOn: Boolean): List<Stroke> {
        if (!capsOn || strokes.isEmpty()) return strokes
        val caps = Stroke(MOD_NONE, KEY_CAPSLOCK)
        return listOf(caps) + strokes + caps
    }

    /**
     * A boot-protocol keyboard with a report id: 8 modifier bits, a reserved
     * byte, six key slots, and the five LED outputs the host may set (Ara
     * ignores them). The descriptor keyboard-emulator apps commonly register.
     */
    val DESCRIPTOR: ByteArray = bytes(
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x06, // Usage (Keyboard)
        0xA1, 0x01, // Collection (Application)
        0x85, REPORT_ID, //   Report ID
        0x05, 0x07, //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0, //   Usage Minimum (Left Control)
        0x29, 0xE7, //   Usage Maximum (Right GUI)
        0x15, 0x00, //   Logical Minimum (0)
        0x25, 0x01, //   Logical Maximum (1)
        0x75, 0x01, //   Report Size (1)
        0x95, 0x08, //   Report Count (8)
        0x81, 0x02, //   Input (Data, Variable, Absolute) — modifiers
        0x95, 0x01, //   Report Count (1)
        0x75, 0x08, //   Report Size (8)
        0x81, 0x01, //   Input (Constant) — reserved byte
        0x95, 0x05, //   Report Count (5)
        0x75, 0x01, //   Report Size (1)
        0x05, 0x08, //   Usage Page (LEDs)
        0x19, 0x01, //   Usage Minimum (Num Lock)
        0x29, 0x05, //   Usage Maximum (Kana)
        0x91, 0x02, //   Output (Data, Variable, Absolute) — LEDs
        0x95, 0x01, //   Report Count (1)
        0x75, 0x03, //   Report Size (3)
        0x91, 0x01, //   Output (Constant) — LED padding
        0x95, 0x06, //   Report Count (6)
        0x75, 0x08, //   Report Size (8)
        0x15, 0x00, //   Logical Minimum (0)
        0x25, 0x65, //   Logical Maximum (101)
        0x05, 0x07, //   Usage Page (Keyboard/Keypad)
        0x19, 0x00, //   Usage Minimum (0)
        0x29, 0x65, //   Usage Maximum (101)
        0x81, 0x00, //   Input (Data, Array) — key slots
        0xC0, // End Collection
    )

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
