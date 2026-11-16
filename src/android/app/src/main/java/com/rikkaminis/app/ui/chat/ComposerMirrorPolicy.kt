package com.rikkaminis.app.ui.chat

/**
 * [voice-ime-1116] The composer's text mirror: who owns the text, and when the
 * ViewModel copy may be copied back into the field.
 *
 * Pure logic with no imports on purpose — the decision is exercised by
 * [com.rikkaminis.app.ui.chat.ComposerMirrorPolicyTest] on a plain JVM, the way
 * [shouldDebounceImeBurst] and [shouldCommitImeBurst] are (see ChatScreenUtils).
 */

/**
 * The text this composer last wrote INTO the ViewModel.
 *
 * Deliberately NOT a Compose `State`: it is read and written only from the main
 * thread (`BasicTextField.onValueChange` and the mirror `LaunchedEffect`) and
 * must not schedule a recomposition of its own.
 */
internal class ComposerSelfWrite {
    var text: String = ""
}

/**
 * Whether the composer's mirror effect should write the ViewModel's text back
 * into the field.
 *
 * The mirror used to key off "field text != ViewModel text", i.e. it read a
 * *difference* as an *intent*. That holds for external writes (draft restore,
 * edit prefill, a refused send putting the text back) but NOT for this
 * composer's own writes. Voice / Pinyin dictation arrives as large bursts, and
 * [shouldDebounceImeBurst] deliberately holds those 150 ms before they reach
 * the ViewModel — so while the user is speaking the ViewModel is structurally
 * behind the field. Every time a flush made the two briefly equal, the next
 * frame re-ran the mirror, wrote the older snapshot back over the field, and
 * dropped the IME's composing region with it. An IME whose composing region is
 * wiped from the outside ends its dictation session: the microphone turns off,
 * the text stops growing, the keyboard stays. On-device (2026-11-16) that is
 * the reported "it just stops mid-sentence, and typing is fine".
 *
 * Three conditions, cheapest first:
 *  1. [vmText] equals [selfWrittenText] → the difference is our own lag, not an
 *     intent. No mirror.
 *  2. [fieldText] already equals [vmText] → nothing to write.
 *  3. [imeDictating] → an IME is recording right now; writing the field from
 *     outside would end that session. Callers pass a lambda so the
 *     `AudioManager` binder call only happens once the two string tests said
 *     "this is a real external write" (rare), not once per dictation burst.
 *
 * Skipping on (3) is the conservative direction: it can only postpone a sync,
 * and the next edit (any `inputText` change) re-evaluates.
 */
internal fun shouldMirrorComposerText(
    vmText: String,
    selfWrittenText: String,
    fieldText: String,
    imeDictating: () -> Boolean,
): Boolean {
    if (vmText == selfWrittenText) return false
    if (fieldText == vmText) return false
    return !imeDictating()
}
